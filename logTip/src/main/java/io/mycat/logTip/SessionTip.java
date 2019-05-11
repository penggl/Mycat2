package io.mycat.logTip;

/**
 * @author jamie12221
 * @date 2019-05-11 21:53
 **/
public enum SessionTip {
  CANNOT_SWITCH_DATANODE("cannot switch dataNode  maybe session in transaction"),
  UNKNOWN_IDLE_RESPONSE("mysql session is idle but it receive response"),
  UNKNOWN_IDLE_CLOSE("mysql session is idle but it closed");
  String message;

  SessionTip(String message) {
    this.message = message;
  }

  public String getMessage() {
    return message;
  }}
