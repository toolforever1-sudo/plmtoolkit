package com.sandisk.plm.tracker.model;

import java.io.Serializable;
import java.sql.Timestamp;

public class ChangeRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    private String itemNumber;
    private String fieldName;
    private String oldValue;
    private String newValue;
    private Timestamp timestamp;
    private String userName;
    private String revNumber;

    public ChangeRecord() {}

    public ChangeRecord(String itemNumber, String fieldName, String oldValue,
                        String newValue, Timestamp timestamp, String userName,
                        String revNumber) {
        this.itemNumber = itemNumber;
        this.fieldName = fieldName;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.timestamp = timestamp;
        this.userName = userName;
        this.revNumber = revNumber;
    }

    public String getItemNumber() { return itemNumber; }
    public void setItemNumber(String itemNumber) { this.itemNumber = itemNumber; }

    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }

    public String getOldValue() { return oldValue; }
    public void setOldValue(String oldValue) { this.oldValue = oldValue; }

    public String getNewValue() { return newValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }

    public Timestamp getTimestamp() { return timestamp; }
    public void setTimestamp(Timestamp timestamp) { this.timestamp = timestamp; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getRevNumber() { return revNumber; }
    public void setRevNumber(String revNumber) { this.revNumber = revNumber; }
}
