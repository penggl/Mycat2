/**
 * Copyright (C) <2019>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.metadata;

import com.alibaba.fastsql.DbType;
import com.alibaba.fastsql.sql.SQLUtils;
import com.alibaba.fastsql.sql.ast.SQLExpr;
import com.alibaba.fastsql.sql.ast.SQLStatement;
import com.alibaba.fastsql.sql.ast.expr.*;
import com.alibaba.fastsql.sql.ast.statement.SQLExprTableSource;
import com.alibaba.fastsql.sql.ast.statement.SQLInsertStatement;
import com.alibaba.fastsql.sql.ast.statement.SQLSelectStatement;
import com.alibaba.fastsql.sql.dialect.mysql.ast.statement.MySqlCreateTableStatement;
import com.alibaba.fastsql.sql.dialect.mysql.ast.statement.MySqlDeleteStatement;
import com.alibaba.fastsql.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.fastsql.sql.dialect.mysql.ast.statement.MySqlUpdateStatement;
import com.alibaba.fastsql.sql.parser.SQLParserUtils;
import com.alibaba.fastsql.sql.parser.SQLStatementParser;
import com.alibaba.fastsql.sql.repository.SchemaObject;
import com.alibaba.fastsql.sql.repository.SchemaRepository;
import com.google.common.collect.ImmutableList;
import io.mycat.*;
import io.mycat.api.collector.RowBaseIterator;
import io.mycat.beans.mycat.JdbcRowMetaData;
import io.mycat.calcite.CalciteConvertors;
import io.mycat.calcite.prepare.MycatSQLPrepareObject;
import io.mycat.config.*;
import io.mycat.datasource.jdbc.JdbcRuntime;
import io.mycat.datasource.jdbc.datasource.DefaultConnection;
import io.mycat.hbt3.CustomTable;
import io.mycat.plug.PlugRuntime;
import io.mycat.plug.loadBalance.LoadBalanceStrategy;
import io.mycat.plug.sequence.SequenceGenerator;
import io.mycat.querycondition.*;
import io.mycat.replica.ReplicaSelectorRuntime;
import io.mycat.router.ShardingTableHandler;
import io.mycat.router.function.PartitionRuleFunctionManager;
import io.mycat.upondb.MycatDBContext;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.alibaba.fastsql.sql.repository.SchemaResolveVisitor.Option.*;
import static io.mycat.calcite.CalciteConvertors.columnInfoListBySQL;
import static io.mycat.calcite.CalciteConvertors.getColumnInfo;

/**
 * @author Junwen Chen
 **/
public enum MetadataManager {
    INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataManager.class);
    final ConcurrentHashMap<String, SchemaHandler> schemaMap = new ConcurrentHashMap<>();

    public final SchemaRepository TABLE_REPOSITORY = new SchemaRepository(DbType.mysql);

    public void removeSchema(String schemaName) {
        schemaMap.remove(schemaName);
    }

    public void addSchema(String schemaName, String dataNode) {
        SchemaHandlerImpl schemaHandler = new SchemaHandlerImpl(schemaName, dataNode);
        schemaMap.computeIfAbsent(schemaName, s -> schemaHandler);
        schemaMap.computeIfAbsent("`" + schemaName + "`", s -> schemaHandler);
    }

    public void addTable(String schemaName, String tableName, ShardingTableConfig tableConfig, List<ShardingQueryRootConfig.BackEndTableInfoConfig> backends, ShardingQueryRootConfig.PrototypeServer prototypeServer) {
        addShardingTable(schemaName, tableName, tableConfig, prototypeServer, getBackendTableInfos(backends));
    }

    public void removeTable(String schemaName, String tableName) {
        SchemaHandler schemaHandler = schemaMap.get(schemaName);
        if (schemaHandler != null) {
            Map<String, TableHandler> stringLogicTableConcurrentHashMap = schemaMap.get(schemaName).logicTables();
            if (stringLogicTableConcurrentHashMap != null) {
                stringLogicTableConcurrentHashMap.remove(tableName);
            }
        }
    }


    public void load(MycatConfig mycatConfig) {
        ShardingQueryRootConfig shardingQueryRootConfig = mycatConfig.getMetadata();
        if (shardingQueryRootConfig != null) {
            //更新新配置里面的信息
            Map<String, ShardingQueryRootConfig.LogicSchemaConfig> schemaConfigMap = shardingQueryRootConfig.getSchemas()
                    .stream()
                    .collect(Collectors.toMap(k -> k.getSchemaName(), v -> v));
            for (Map.Entry<String, ShardingQueryRootConfig.LogicSchemaConfig> entry : schemaConfigMap.entrySet()) {
                String orignalSchemaName = entry.getKey();
                ShardingQueryRootConfig.LogicSchemaConfig value = entry.getValue();
                String targetName = value.getTargetName();
                final String schemaName = orignalSchemaName;
                addSchema(schemaName, targetName);
                for (Map.Entry<String, ShardingTableConfig> e : value.getShadingTables().entrySet()) {
                    String tableName = e.getKey();
                    ShardingTableConfig tableConfigEntry = e.getValue();
                    addShardingTable(schemaName, tableName,
                            tableConfigEntry,
                            shardingQueryRootConfig.getPrototype(),
                            getBackendTableInfos(tableConfigEntry.getDataNodes()));
                }

                for (Map.Entry<String, GlobalTableConfig> e : value.getGlobalTables().entrySet()) {
                    String tableName = e.getKey();
                    GlobalTableConfig tableConfigEntry = e.getValue();
                    List<DataNode> backendTableInfos = tableConfigEntry.getDataNodes().stream().map(i -> new BackendTableInfo(i.getTargetName(), schemaName, tableName)).collect(Collectors.toList());
                    addGlobalTable(schemaName, tableName,
                            tableConfigEntry,
                            shardingQueryRootConfig.getPrototype(),
                            backendTableInfos
                    );
                }
                for (Map.Entry<String, NormalTableConfig> e : value.getNormalTables().entrySet()) {
                    String tableName = e.getKey();
                    NormalTableConfig tableConfigEntry = e.getValue();
                    addNormalTable(schemaName, tableName,
                            tableConfigEntry,
                            shardingQueryRootConfig.getPrototype()
                    );
                }
                for (Map.Entry<String, CustomTableConfig> e : value.getCustomTables().entrySet()) {
                    String tableName = e.getKey();
                    CustomTableConfig tableConfigEntry = e.getValue();
                    addCustomTable(schemaName, tableName,
                            tableConfigEntry,
                            shardingQueryRootConfig.getPrototype()
                    );
                }
            }
            //去掉失效的配置
            //Map<String, SchemaHandler> schemaMap = this.getSchemaMap();
            //配置里面不存在的库移除
            new ArrayList<>(schemaMap.keySet()).stream().filter(currentSchema ->
                    !schemaConfigMap.containsKey(currentSchema) && !schemaConfigMap.containsKey("`" + currentSchema + "`")
            ).forEach(schemaMap::remove);

            //配置里面不存在的表移除
            for (Map.Entry<String, SchemaHandler> entry : schemaMap.entrySet()) {
                SchemaHandler schemaHandler = entry.getValue();
                Set<String> tableNames = new HashSet<>(schemaHandler.logicTables().keySet());
                Set<String> set = schemaConfigMap.values().stream()
                        .flatMap(i -> Stream.concat(Stream.concat(
                                i.getGlobalTables().keySet().stream(),
                                i.getShadingTables().keySet().stream()),
                                i.getNormalTables().keySet().stream())).collect(Collectors.toSet());
                for (String tableName : tableNames) {
                    if (!set.contains(tableName) && !set.contains("`" + tableName + "`")) {
                        schemaHandler.logicTables().remove(tableName);
                    }
                }
            }
        }
    }

    private void addCustomTable(String schemaName,
                                String tableName,
                                CustomTableConfig tableConfigEntry,
                                ShardingQueryRootConfig.PrototypeServer prototypeServer) {
        String createTableSQL = tableConfigEntry.getCreateTableSQL();
        String clazz = tableConfigEntry.getClazz();
        try {
            Class<?> aClass = Class.forName(clazz);
            Constructor<?> declaredConstructor = aClass.getDeclaredConstructor(
                    String.class,
                    String.class,
                    String.class,
                    Map.class,
                    List.class);
            CustomTable o = (CustomTable)declaredConstructor.newInstance(
                    schemaName,
                    tableName,
                    createTableSQL,
                    tableConfigEntry.getKvOptions(),
                    tableConfigEntry.getListOptions());
            addLogicTable(LogicTable.createCustomTable(o));

        } catch (ClassNotFoundException | NoSuchMethodException e) {
            LOGGER.error("",e);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    private void addNormalTable(String schemaName,
                                String tableName,
                                NormalTableConfig tableConfigEntry,
                                ShardingQueryRootConfig.PrototypeServer prototypeServer) {
        //////////////////////////////////////////////
        NormalTableConfig.BackEndTableInfoConfig dataNode = tableConfigEntry.getDataNode();
        List<DataNode> dataNodes = ImmutableList.of(new BackendTableInfo(dataNode.getTargetName(),
              Optional.ofNullable( dataNode.getSchemaName()).orElse(schemaName),
                Optional.ofNullable(  dataNode.getTableName()).orElse(tableName)));
        String createTableSQL = Optional.ofNullable(tableConfigEntry.getCreateTableSQL())
                .orElseGet(() -> getCreateTableSQLByJDBC(schemaName, tableName, dataNodes));
        List<SimpleColumnInfo> columns = getSimpleColumnInfos(prototypeServer, schemaName, tableName, createTableSQL, dataNodes);
        addLogicTable(LogicTable.createNormalTable(schemaName, tableName, dataNodes.get(0), columns, createTableSQL));
    }

    private void addGlobalTable(String schemaName,
                                String orignalTableName,
                                GlobalTableConfig tableConfigEntry,
                                ShardingQueryRootConfig.PrototypeServer prototypeServer,
                                List<DataNode> backendTableInfos) {
        //////////////////////////////////////////////
        final String tableName = orignalTableName;
        String createTableSQL = Optional.ofNullable(tableConfigEntry.getCreateTableSQL())
                .orElseGet(() -> getCreateTableSQLByJDBC(schemaName, orignalTableName, backendTableInfos));
        List<SimpleColumnInfo> columns = getSimpleColumnInfos(prototypeServer, schemaName, tableName, createTableSQL, backendTableInfos);
        //////////////////////////////////////////////

        LoadBalanceStrategy loadBalance = PlugRuntime.INSTANCE.getLoadBalanceByBalanceName(tableConfigEntry.getBalance());

        addLogicTable(LogicTable.createGlobalTable(schemaName, tableName, backendTableInfos, loadBalance, columns, createTableSQL));
    }


    @SneakyThrows
    MetadataManager() {

    }

    private List<DataNode> getBackendTableInfos(List<ShardingQueryRootConfig.BackEndTableInfoConfig> stringListEntry) {
        if (stringListEntry == null) {
            return Collections.emptyList();
        }
        return stringListEntry.stream().map(t -> {
            SchemaInfo schemaInfo = new SchemaInfo(t.getSchemaName(), t.getTableName());
            return new BackendTableInfo(t.getTargetName(), schemaInfo);
        }).collect(Collectors.toList());
    }

    private synchronized void accrptDDL(String schemaName, String sql) {
        TABLE_REPOSITORY.setDefaultSchema(schemaName);
        TABLE_REPOSITORY.acceptDDL(sql);
    }

    @SneakyThrows
    private void addShardingTable(String schemaName,
                                  String orignalTableName,
                                  ShardingTableConfig tableConfigEntry,
                                  ShardingQueryRootConfig.PrototypeServer prototypeServer,
                                  List<DataNode> backends) {
        //////////////////////////////////////////////
        String createTableSQL = Optional.ofNullable(tableConfigEntry.getCreateTableSQL()).orElseGet(() -> getCreateTableSQLByJDBC(schemaName, orignalTableName, backends));
        List<SimpleColumnInfo> columns = getSimpleColumnInfos(prototypeServer, schemaName, orignalTableName, createTableSQL, backends);
        //////////////////////////////////////////////
        String s = schemaName + "_" + orignalTableName;
        Supplier<String> sequence = SequenceGenerator.INSTANCE.getSequence(s);
        if (sequence == null) {
            sequence = SequenceGenerator.INSTANCE.getSequence(orignalTableName.toUpperCase());
        }

        ShardingTable shardingTable = LogicTable.createShardingTable(schemaName, orignalTableName, backends, columns, null, sequence, createTableSQL);
        shardingTable.setShardingFuntion(PartitionRuleFunctionManager.INSTANCE.getRuleAlgorithm(shardingTable, tableConfigEntry.getFunction()));
        addLogicTable(shardingTable);
    }

    private void addLogicTable(TableHandler logicTable) {
        String schemaName = logicTable.getSchemaName();
        String tableName = logicTable.getTableName();
        String createTableSQL = logicTable.getCreateTableSQL();
        Map<String, TableHandler> tableMap;
        tableMap = schemaMap.get(schemaName).logicTables();
        tableMap.put(tableName, logicTable);
        tableMap.put("`" + tableName + "`", logicTable);

        tableMap = schemaMap.get(schemaName).logicTables();
        tableMap.put(tableName, logicTable);
        tableMap.put("`" + tableName + "`", logicTable);
        accrptDDL(schemaName, createTableSQL);
    }


    private List<SimpleColumnInfo> getSimpleColumnInfos(ShardingQueryRootConfig.PrototypeServer prototypeServer,
                                                        String schemaName,
                                                        String tableName,
                                                        String createTableSQL,
                                                        List<DataNode> backends) {
        List<SimpleColumnInfo> columns = null;
        /////////////////////////////////////////////////////////////////////////////////////////////////

        /////////////////////////////////////////////////////////////////////////////////////////////////
        if (createTableSQL != null) {
            try {
                columns = getColumnInfo(createTableSQL);
            } catch (Throwable e) {
                LOGGER.error("无法根据建表sql:{},获取字段信息", createTableSQL, e);
            }
        }
        ////////////////////////////////////////////////////////////////////////////////////////////////
        if (columns == null && backends != null && !backends.isEmpty()) {
            try {
                columns = getColumnInfoBySelectSQLOnJdbc(backends);
            } catch (Throwable e) {
                LOGGER.error("无法根据建表sql:{},获取字段信息", createTableSQL, e);
            }
        }
        ////////////////////////////////////////////////////////////////////////////////////////////////
        if (columns == null && prototypeServer != null) {
            try {
                columns = CalciteConvertors.getSimpleColumnInfos(schemaName, tableName, prototypeServer.getTargetName());
            } catch (Throwable e) {
                LOGGER.error("无法根据建表sql:{},获取字段信息", createTableSQL, e);
            }
        }
        ////////////////////////////////////////////////////////////////////////////////////////////////
        if (columns == null && backends != null && !backends.isEmpty()) {
            try {
                DataNode backendTableInfo = backends.get(0);
                String targetName = backendTableInfo.getTargetName();
                String schema = backendTableInfo.getSchema();
                String table = backendTableInfo.getTable();
                targetName = ReplicaSelectorRuntime.INSTANCE.getDatasourceNameByReplicaName(targetName, false, null);
                try (DefaultConnection connection = JdbcRuntime.INSTANCE.getConnection(targetName)) {
                    DatabaseMetaData metaData = connection.getRawConnection().getMetaData();
                    return CalciteConvertors.convertfromDatabaseMetaData(metaData, schema, schema, table);
                }
            } catch (Throwable e) {
                LOGGER.error("无法根据建表sql:{},获取字段信息", createTableSQL, e);
            }
        }
        ////////////////////////////////////////////////////////////////////////////////////////////////
        if (columns == null) {
            throw new UnsupportedOperationException("没有配置建表sql");
        }
        return columns;
    }

    private List<SimpleColumnInfo> getColumnInfoBySelectSQLOnJdbc(List<DataNode> backends) {
        if (backends.isEmpty()) {
            return null;
        }
        DataNode backendTableInfo = backends.get(0);
        String targetName = backendTableInfo.getTargetName();
        String targetSchemaTable = backendTableInfo.getTargetSchemaTable();
        String name = ReplicaSelectorRuntime.INSTANCE.getDatasourceNameByReplicaName(targetName, true, null);
        try (DefaultConnection connection = JdbcRuntime.INSTANCE.getConnection(name)) {
            Connection rawConnection = connection.getRawConnection();
            String sql = "select * from " + targetSchemaTable + " where 0 ";
            try (Statement statement = rawConnection.createStatement()) {
                statement.setMaxRows(0);
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    resultSet.next();
                    ResultSetMetaData metaData = resultSet.getMetaData();
                    JdbcRowMetaData jdbcRowMetaData = new JdbcRowMetaData(metaData);
                    return getColumnInfo(jdbcRowMetaData);
                }
            }
        } catch (Throwable e) {
            LOGGER.error("无法根据jdbc连接获取建表sql:{} {}", backends, e);
        }
        return null;
    }

    private static String getCreateTableSQLByJDBC(String schemaName, String tableName, List<DataNode> backends) {
        if (backends == null || backends.isEmpty()) {
            return null;
        }
        for (DataNode backend : backends) {
            try {
                DataNode backendTableInfo = backends.get(0);
                String targetName = backendTableInfo.getTargetName();
                String targetSchemaTable = backendTableInfo.getTargetSchemaTable();
                String name = ReplicaSelectorRuntime.INSTANCE.getDatasourceNameByReplicaName(targetName, true, null);
                try (DefaultConnection connection = JdbcRuntime.INSTANCE.getConnection(name)) {
                    String sql = "SHOW CREATE TABLE " + targetSchemaTable;
                    try (RowBaseIterator rowBaseIterator = connection.executeQuery(sql)) {
                        while (rowBaseIterator.next()) {
                            String string = rowBaseIterator.getString(2);
                            SQLStatement sqlStatement = SQLUtils.parseSingleMysqlStatement(string);
                            MySqlCreateTableStatement sqlStatement1 = (MySqlCreateTableStatement) sqlStatement;

                            sqlStatement1.setTableName(SQLUtils.normalize(tableName));
                            sqlStatement1.setSchema(SQLUtils.normalize(schemaName));//顺序不能颠倒
                            return sqlStatement1.toString();
                        }
                    }
                }
            } catch (Throwable e) {
                LOGGER.error("can not get create table sql from:"+backend.getTargetName()+backend.getTargetSchemaTable(),e);
               continue;
            }
        }


        return null;
    }


    //////////////////////////////////////////////////////function/////////////////////////////////////////////////////

    public static Iterable<Map<String, List<String>>> routeInsert(String currentSchema, String sql) {
        SQLStatementParser sqlStatementParser = SQLParserUtils.createSQLStatementParser(sql, DbType.mysql);
        List list = new LinkedList();
        sqlStatementParser.parseStatementList(list);
        return MetadataManager.INSTANCE.getInsertInfoIterator(currentSchema, (Iterator<MySqlInsertStatement>) list.iterator());
    }

    public static Map<String, List<String>> routeInsertFlat(String currentSchema, String sql) {
        Iterable<Map<String, List<String>>> maps = routeInsert(currentSchema, sql);
        HashMap<String, List<String>> res = new HashMap<>();
        for (Map<String, List<String>> map : maps) {
            for (Map.Entry<String, List<String>> e : map.entrySet()) {
                List<String> strings = res.computeIfAbsent(e.getKey(), s -> new ArrayList<>());
                strings.addAll(e.getValue());
            }
        }

        return res;
    }

    public Iterable<Map<String, List<String>>> getInsertInfoIterator(String currentSchemaNameText, Iterator<MySqlInsertStatement> listIterator) {
        final String currentSchemaName = currentSchemaNameText;
        return () -> new Iterator<Map<String, List<String>>>() {
            @Override
            public boolean hasNext() {
                return listIterator.hasNext();
            }

            @Override
            public Map<String, List<String>> next() {
                MySqlInsertStatement statement = listIterator.next();//会修改此对象
                Map<DataNode, List<SQLInsertStatement.ValuesClause>> res = getInsertInfoValuesClause(currentSchemaNameText, statement);
                listIterator.remove();

                //////////////////////////////////////////////////////////////////
                Map<String, List<String>> map = new HashMap<>();
                for (Map.Entry<DataNode, List<SQLInsertStatement.ValuesClause>> entry : res.entrySet()) {
                    DataNode dataNode = entry.getKey();
                    SQLExprTableSource tableSource = statement.getTableSource();
                    tableSource.setExpr(new SQLPropertyExpr(dataNode.getSchema(), dataNode.getTable()));
                    statement.getValuesList().clear();
                    statement.getValuesList().addAll(entry.getValue());
                    List<String> list = map.computeIfAbsent(dataNode.getTargetName(), s12 -> new ArrayList<>());
                    list.add(statement.toString());
                }
                return map;
            }
        };
    }

    //////////////////////////////////////////////////////function/////////////////////////////////////////////////////
    public Map<String, List<String>> getInsertInfoMap(String currentSchemaName, String statement) {
        SQLStatementParser sqlStatementParser = SQLParserUtils.createSQLStatementParser(statement, DbType.mysql);
        MySqlInsertStatement sqlStatement = (MySqlInsertStatement) sqlStatementParser.parseStatement();
        return getInsertInfoMap(currentSchemaName, sqlStatement);
    }

    public Map<String, List<String>> getInsertInfoIter(String currentSchemaName, String statement) {
        SQLStatementParser sqlStatementParser = SQLParserUtils.createSQLStatementParser(statement, DbType.mysql);
        MySqlInsertStatement sqlStatement = (MySqlInsertStatement) sqlStatementParser.parseStatement();
        return getInsertInfoMap(currentSchemaName, sqlStatement);
    }

    public Map<String, List<String>> getInsertInfoMap(String currentSchemaName, MySqlInsertStatement statement) {
        Map<String, List<String>> res = new HashMap<>();
        Map<DataNode, List<SQLInsertStatement.ValuesClause>> insertInfo = getInsertInfoValuesClause(currentSchemaName, statement);
        SQLExprTableSource tableSource = statement.getTableSource();
        for (Map.Entry<DataNode, List<SQLInsertStatement.ValuesClause>> backendTableInfoListEntry : insertInfo.entrySet()) {
            statement.getValuesList().clear();
            DataNode key = backendTableInfoListEntry.getKey();
            statement.getValuesList().addAll(backendTableInfoListEntry.getValue());
            tableSource.setExpr(new SQLPropertyExpr(key.getSchema(), key.getTable()));
            List<String> strings = res.computeIfAbsent(key.getTargetName(), s -> new ArrayList<>());
            strings.add(statement.toString());
        }
        return res;
    }

    public Map<DataNode, List<SQLInsertStatement.ValuesClause>> getInsertInfoValuesClause(String currentSchemaName, String statement) {
        SQLStatementParser sqlStatementParser = SQLParserUtils.createSQLStatementParser(statement, DbType.mysql);
        MySqlInsertStatement sqlStatement = (MySqlInsertStatement) sqlStatementParser.parseStatement();
        return getInsertInfoValuesClause(currentSchemaName, sqlStatement);
    }

    public Map<DataNode, List<SQLInsertStatement.ValuesClause>> getInsertInfoValuesClause(String currentSchemaName, MySqlInsertStatement statement) {
        String s = statement.getTableSource().getSchema();
        String schema = SQLUtils.normalize(s == null ? currentSchemaName : s);
        String tableName = SQLUtils.normalize(statement.getTableSource().getTableName());
        TableHandler logicTable = schemaMap.get(Objects.requireNonNull(schema)).logicTables().get(tableName);
        if (!(logicTable instanceof ShardingTableHandler)) {
            throw new AssertionError();
        }
        List<SQLExpr> columns = statement.getColumns();
        Iterable<SQLInsertStatement.ValuesClause> originValuesList = statement.getValuesList();
        Iterable<SQLInsertStatement.ValuesClause> outValuesList;
        List<SimpleColumnInfo> simpleColumnInfos;
        if (columns == null) {
            simpleColumnInfos = logicTable.getColumns();
        } else {
            simpleColumnInfos = new ArrayList<>(logicTable.getColumns().size());
            for (SQLExpr column : columns) {
                String columnName = SQLUtils.normalize(column.toString());
                try {
                    SimpleColumnInfo columnByName = Objects.requireNonNull(logicTable.getColumnByName(columnName));
                    simpleColumnInfos.add(columnByName);
                } catch (NullPointerException e) {
                    throw new MycatException("未知字段:" + columnName);
                }
            }
        }
        Supplier<String> stringSupplier = logicTable.nextSequence();
        if (logicTable.isAutoIncrement() && stringSupplier != null) {
            if (!simpleColumnInfos.contains(logicTable.getAutoIncrementColumn())) {
                simpleColumnInfos.add(logicTable.getAutoIncrementColumn());
                ///////////////////////////////修改参数//////////////////////////////
                statement.getColumns().add(new SQLIdentifierExpr(logicTable.getAutoIncrementColumn().getColumnName()));
                ///////////////////////////////修改参数//////////////////////////////
                outValuesList = () -> StreamSupport.stream(originValuesList.spliterator(), false)
                        .peek(i -> i.getValues()
                                .add(SQLExprUtils.fromJavaObject(stringSupplier.get())))
                        .iterator();
            } else {
                int index = simpleColumnInfos.indexOf(logicTable.getAutoIncrementColumn());
                outValuesList = () -> StreamSupport.stream(originValuesList.spliterator(), false)
                        .peek(i -> {
                            List<SQLExpr> values = i.getValues();
                            SQLExpr sqlExpr = values.get(index);
                            if (sqlExpr instanceof SQLNullExpr || sqlExpr == null) {
                                values.set(index, SQLExprUtils.fromJavaObject(stringSupplier.get()));
                            }
                        })
                        .iterator();
            }
        } else {
            outValuesList = originValuesList;
        }

        return getBackendTableInfoListMap(simpleColumnInfos, (ShardingTableHandler) logicTable, outValuesList);
    }

    public Map<DataNode, List<SQLInsertStatement.ValuesClause>> getBackendTableInfoListMap(List<SimpleColumnInfo> columns, ShardingTableHandler logicTable, Iterable<SQLInsertStatement.ValuesClause> valuesList) {
        int index;
        HashMap<DataNode, List<SQLInsertStatement.ValuesClause>> res = new HashMap<>(1);
        for (SQLInsertStatement.ValuesClause valuesClause : valuesList) {
            DataMappingEvaluator dataMappingEvaluator = new DataMappingEvaluator();
            index = 0;
            for (SQLExpr valueText : valuesClause.getValues()) {
                SimpleColumnInfo simpleColumnInfo = columns.get(index);
                if (valueText instanceof SQLValuableExpr) {
                    String value = SQLUtils.normalize(Objects.toString(((SQLValuableExpr) valueText).getValue()));
                    dataMappingEvaluator.assignment( simpleColumnInfo.getColumnName(), value);
                }  //                    throw new UnsupportedOperationException();

                index++;
            }
            List<DataNode> calculate = logicTable.function().calculate(dataMappingEvaluator.getColumnMap());
            if (calculate.size() != 1) {
                throw new UnsupportedOperationException("插入语句多于1个目标:" + valuesList);
            }
            DataNode endTableInfo = calculate.get(0);
            List<SQLInsertStatement.ValuesClause> valuesGroup = res.computeIfAbsent(endTableInfo, backEndTableInfo -> new ArrayList<>(1));
            valuesGroup.add(valuesClause);
        }
        return res;
    }

    public Map<String, List<String>> rewriteSQL(String currentSchema, String sql) {
        SQLStatement sqlStatement = SQLUtils.parseSingleMysqlStatement(sql);
        resolveMetadata(sqlStatement);
        ConditionCollector conditionCollector = new ConditionCollector();
        sqlStatement.accept(conditionCollector);
        Rrs rrs = assignment(conditionCollector.getRootQueryDataRange(), currentSchema);
        Map<String, List<String>> sqls = new HashMap<>();
        for (DataNode endTableInfo : rrs.getBackEndTableInfos()) {
            SQLExprTableSource table = rrs.getTable();
            table.setExpr(new SQLPropertyExpr(endTableInfo.getSchema(), endTableInfo.getTable()));
            List<String> list = sqls.computeIfAbsent(endTableInfo.getTargetName(), s -> new ArrayList<>());
            list.add(SQLUtils.toMySqlString(sqlStatement));
        }
        return sqls;
    }

    public void resolveMetadata(SQLStatement sqlStatement) {
        TABLE_REPOSITORY.resolve(sqlStatement, ResolveAllColumn, ResolveIdentifierAlias, CheckColumnAmbiguous);
    }

    //////////////////////////////////////////calculate///////////////////////////////
    private Rrs assignment(
            QueryDataRange queryDataRange, String wapperSchemaName) {
        String schemaName = wapperSchemaName;
        String tableName = null;
        SQLExprTableSource table = null;
        if (queryDataRange.getTableSource() != null) {
            table = queryDataRange.getTableSource();
            SchemaObject schemaObject = Objects.requireNonNull(table.getSchemaObject(), "meet unknown table " + table);
            schemaName = SQLUtils.normalize(schemaObject.getSchema().getName());
            tableName = SQLUtils.normalize(schemaObject.getName());
        }
        ShardingTableHandler logicTable = (ShardingTableHandler) schemaMap.get(schemaName).logicTables().get(tableName);
        DataMappingEvaluator dataMappingEvaluator = new DataMappingEvaluator();

        for (ColumnValue equalValue : queryDataRange.getEqualValues()) {
            dataMappingEvaluator.assignment(equalValue.getColumn().computeAlias(), Objects.toString(equalValue.getValue()));

        }
        List<ColumnRangeValue> rangeValues1 = queryDataRange.getRangeValues();
        for (ColumnRangeValue columnRangeValue : rangeValues1) {
            dataMappingEvaluator.assignmentRange( columnRangeValue.getColumn().computeAlias(), Objects.toString(columnRangeValue.getBegin()), Objects.toString(columnRangeValue.getEnd()));
        }
        List<DataNode> calculate = logicTable.function().calculate(dataMappingEvaluator.getColumnMap());
        return new Rrs(calculate, table);
    }


    public static class Rrs {
        Collection<DataNode> backEndTableInfos;
        SQLExprTableSource table;

        public Rrs(Collection<DataNode> backEndTableInfos, SQLExprTableSource table) {
            this.backEndTableInfos = backEndTableInfos;
            this.table = table;
        }

        public Collection<DataNode> getBackEndTableInfos() {
            return backEndTableInfos;
        }

        public SQLExprTableSource getTable() {
            return table;
        }
    }

    public TableHandler getTable(String schemaName, String tableName) {
        return Optional.ofNullable(schemaMap).map(i -> i.get(schemaName)).map(i -> i.logicTables().get(tableName)).orElse(null);
    }

    public Map<String, SchemaHandler> getSchemaMap() {
        return (Map) schemaMap;
    }

    public List<String> showDatabases() {
        return schemaMap.keySet().stream().map(i -> SQLUtils.normalize(i)).distinct().sorted(Comparator.comparing(s -> s)).collect(Collectors.toList());
    }

    public MetadataManager clear() {
        this.schemaMap.clear();
        return this;
    }

}