/*
 * Copyright (C) 2012 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.tables.sms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Interval;
import org.opendatakit.common.android.provider.DataTableColumns;
import org.opendatakit.tables.data.ColumnProperties;
import org.opendatakit.tables.data.ColumnType;
import org.opendatakit.tables.data.DataUtil;
import org.opendatakit.tables.data.DbHelper;
import org.opendatakit.tables.data.DbTable;
import org.opendatakit.tables.data.KeyValueStore;
import org.opendatakit.tables.data.Query;
import org.opendatakit.tables.data.Query.Constraint;
import org.opendatakit.tables.data.TableProperties;
import org.opendatakit.tables.data.UserTable;
import org.opendatakit.tables.utils.SecurityUtil;
import org.opendatakit.tables.utils.ShortcutUtil;

import android.util.Log;


/**
 * A class for handling incoming messages.
 */
public class MsgHandler {

    private static final String SPACE_QUESTION_MARK_SYMBOL = " ?";

	private static final String SPACE_TILDE_SYMBOL = " ~";

	private static final String SPACE_SLASH_SYMBOL = " /";

	private static final String SPACE_BANG_SYMBOL = " !";

	private static final String SPACE_GREATER_THAN_SYMBOL = " >";

	private static final String SPACE_LESS_THAN_SYMBOL = " <";

	private static final String SPACE_EQUALS_SYMBOL = " =";

	private static final String SPACE_PLUS_SYMBOL = " +";

	private static final String EMPTY_STRING = "";

	private static final String AT_SYMBOL = "@";

	private static final char SPACE_CHAR = ' ';

	private static final char HASH_SYMBOL_CHAR = '#';

	private static final String PLUS_SYMBOL = "+";

	private static final String PERCENT_SYMBOL = "%";

	private static final String SPACE = " ";

	private enum Type {ADD, QUERY}

    private static final int DEFAULT_LIMIT = 25;

    private DataUtil du;
    private DbHelper dbh;
    private TableProperties[] tps;
    private TableProperties[] dataTps;
    private TableProperties[] scTps;
    private SMSSender smsSender;

    public MsgHandler(DbHelper dbh, SMSSender smsSender) {
        this.du = DataUtil.getDefaultDataUtil();
        this.dbh = dbh;
        this.smsSender = smsSender;
    }

    public boolean handleMessage(String msg, String phoneNum) {
        Log.d("MSGH", "handling message: " + msg);
        if (!checkIsMessage(msg)) {
            Log.d("MSGH", "is not message for ODK Tables");
            return false;
        }
        init();
        msg = standardize(msg);
        Log.d("MSGH", "standardized message is: " + msg);
        TableProperties tp = findTable(msg);
        if (tp == null) {
            return false;
        }
        Type type = determineType(msg);
        if (type == null) {
            return false;
        }
        if (!checkSecurity(msg, phoneNum, tp, type)) {
            return false;
        }
        msg = stripPassword(msg);
        switch (type) {
        case ADD:
            return handleAdd(tp, msg, phoneNum);
        case QUERY:
            return handleQuery(tp, msg, phoneNum);
        default:
            return false;
        }
    }

    private void init() {
        tps = TableProperties.getTablePropertiesForAll(dbh,
            KeyValueStore.Type.ACTIVE);
        dataTps = TableProperties.getTablePropertiesForDataTables(dbh,
            KeyValueStore.Type.ACTIVE);
        // TODO: verify that the tables returned are still valid (that columns haven't been removed or renamed)
        scTps = TableProperties.getTablePropertiesForShortcutTables(dbh,
            KeyValueStore.Type.ACTIVE);
        Log.d("MSGH", "scTps:" + Arrays.toString(scTps));
    }

    private boolean checkIsMessage(String msg) {
        return msg.startsWith(AT_SYMBOL) && (msg.lastIndexOf(SPACE_CHAR) > 0);
    }

    private String standardize(String msg) {
        msg = msg.trim();
        List<String> scNames = new ArrayList<String>();
        List<String> scInputs = new ArrayList<String>();
        List<String> scOutputs = new ArrayList<String>();
        for (TableProperties scTp : scTps) {
        	ColumnProperties cpLabel = scTp.getColumnByDisplayName(ShortcutUtil.LABEL_COLUMN_NAME);
        	ColumnProperties cpInput = scTp.getColumnByDisplayName(ShortcutUtil.INPUT_COLUMN_NAME);
        	ColumnProperties cpOutput = scTp.getColumnByDisplayName(ShortcutUtil.OUTPUT_COLUMN_NAME);
            String[] scCols = new String[] {
            		cpLabel.getElementKey(),
            		cpInput.getElementKey(),
            		cpOutput.getElementKey()};
            DbTable dbt = DbTable.getDbTable(dbh, scTp);
            UserTable table = dbt.getRaw(new Query(tps, scTp), scCols);
            for (int i = 0; i < table.getHeight(); i++) {
                scNames.add(table.getData(i, 0));
                scInputs.add(table.getData(i, 1));
                scOutputs.add(table.getData(i, 2));
            }
        }
        int iterCount = 0;
        while (iterCount < scNames.size()) {
            String[] split = msg.split(SPACE);
            String target = split[0].substring(1);
            for (int j = 0; j < scNames.size(); j++) {
                if (!target.equals(scNames.get(j))) {
                    continue;
                }
                String nextMsg = convertByShortcut(msg, scInputs.get(j),
                        scOutputs.get(j));
                if ((nextMsg != null) && checkIsMessage(nextMsg)) {
                    msg = nextMsg.trim();
                    break;
                }
            }
            iterCount++;
        }
        return msg;
    }

    private String convertByShortcut(String msg, String input, String output) {
        msg = msg.substring(msg.indexOf(SPACE_CHAR) + 1);
        Map<String, String> values = new HashMap<String, String>();
        String[] inSplit = input.split(PERCENT_SYMBOL);
        if (!msg.startsWith(inSplit[0])) {
            return null;
        }
        int index = inSplit[0].length();
        for (int i = 1; i < inSplit.length - 1; i += 2) {
            int nextIndex = msg.indexOf(inSplit[i + 1], index);
            if (nextIndex < 0) {
                return null;
            }
            values.put(inSplit[i], msg.substring(index, nextIndex));
            index = nextIndex + inSplit[i + 1].length();
        }
        if (index != msg.length()) {
            values.put(inSplit[inSplit.length - 1], msg.substring(index));
        }
        String[] outSplit = output.split(PERCENT_SYMBOL);
        int start = output.startsWith(PERCENT_SYMBOL) ? 0 : 1;
        for (int i = start; i < outSplit.length; i += 2) {
            String key = outSplit[i];
            if (!values.containsKey(key)) {
                return null;
            }
            outSplit[i] = values.get(key);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : outSplit) {
            sb.append(s);
        }
        return sb.toString();
    }

    private TableProperties findTable(String msg) {
        String target = msg.split(SPACE)[0].substring(1);
        for (TableProperties tp : dataTps) {
            if (tp.getDisplayName().equals(target)) {
                return tp;
            }
        }
        return null;
    }

    private Type determineType(String msg) {
        String[] split = msg.split(SPACE);
        if (split.length < 2) {
            return null;
        }
        if (split[1].startsWith(PLUS_SYMBOL)) {
            return Type.ADD;
        } else {
            return Type.QUERY;
        }
    }

    private boolean checkSecurity(String msg, String phoneNum,
            TableProperties tp, Type type) {
        String secTableId;
        secTableId = tp.getAccessControls();
        // TODO fix this
//        if (type == Type.ADD) {
//            secTableId = tp.getWriteSecurityTableId();
//        } else {
//            secTableId = tp.getReadSecurityTableId();
//        }
        if (secTableId == null) {
            return true;
        }
        String password = EMPTY_STRING;
        int lastHashIndex = msg.lastIndexOf(HASH_SYMBOL_CHAR);
        if ((lastHashIndex > 0) && (msg.length() > lastHashIndex + 2) &&
                (msg.charAt(lastHashIndex - 1) == SPACE_CHAR)) {
            password = msg.substring(lastHashIndex + 1);
        }
        DbTable sDbt = DbTable.getDbTable(dbh,
            TableProperties.getTablePropertiesForTable(dbh, secTableId,
                KeyValueStore.Type.ACTIVE));
        ArrayList<String> columns = new ArrayList<String>();
        columns.add(SecurityUtil.PASSWORD_COLUMN_NAME);
        UserTable table = sDbt.getRaw(
        		columns,
        		new String[] {DataTableColumns.SAVED, SecurityUtil.PHONENUM_COLUMN_NAME},
                new String[] {DbTable.SavedStatus.COMPLETE.name(), phoneNum}, null);
        for (int i = 0; i < table.getHeight(); i++) {
            if (password.equals(table.getData(i, 0))) {
                return true;
            }
        }
        return false;
    }

    private String stripPassword(String msg) {
        int lastSpaceIndex = msg.lastIndexOf(SPACE_CHAR);
        if ((msg.length() > lastSpaceIndex + 1) &&
                (msg.charAt(lastSpaceIndex + 1) == HASH_SYMBOL_CHAR)) {
            return msg.substring(0, lastSpaceIndex).trim();
        }
        return msg;
    }

    private boolean handleAdd(TableProperties tp, String msg,
            String phoneNum) {
        Map<String, String> values = new HashMap<String, String>();
        int plusIndex = msg.indexOf(SPACE_PLUS_SYMBOL) + 1;
        while (plusIndex > 0) {
            // finding index of space between column and value
            int spaceIndex = msg.indexOf(SPACE_CHAR, plusIndex + 2);
            if (spaceIndex < 0) {
                return false;
            }
            // getting the column name
            String key = msg.substring(plusIndex + 1, spaceIndex).trim();
            int secondSpaceIndex = msg.indexOf(SPACE_CHAR, spaceIndex + 1);
            if (secondSpaceIndex < 0) {
                // we've gotten to the end of the message; the remainder must
                // be the value
                String value = msg.substring(spaceIndex).trim();
                values.put(key, value);
                break;
            }
            plusIndex = msg.indexOf(SPACE_PLUS_SYMBOL, secondSpaceIndex) + 1;
            if (plusIndex < 1) {
                // we got to the end of the message
                String value = msg.substring(spaceIndex).trim();
                values.put(key, value);
                break;
            }
            String value = msg.substring(spaceIndex, plusIndex).trim();
            values.put(key, value);
        }
        Map<String, String> rowValues = new HashMap<String, String>();
        for (String key : values.keySet()) {
            ColumnProperties cp = tp.getColumnByUserLabel(key);
            if (cp == null) {
                return false;
            }
            String value = du.validifyValue(cp, values.get(key));
            if (value == null) {
                return false;
            } else if (cp.getSmsIn()) {
                rowValues.put(cp.getElementKey(), value);
            }
        }
        DbTable dbt = DbTable.getDbTable(dbh, tp);
        dbt.addRow(rowValues, null, null, phoneNum, null /* instanceName */,
            null /* formId */, null /* locale */);
        return true;
    }

    private boolean handleQuery(TableProperties tp, String msg,
            String phoneNum) {
        List<Integer> indices = new ArrayList<Integer>();
        int spaceIndex = msg.indexOf(SPACE_CHAR);
        int charIndex = minNonNegative(msg.indexOf(SPACE_EQUALS_SYMBOL, spaceIndex),
                msg.indexOf(SPACE_LESS_THAN_SYMBOL, spaceIndex),
                msg.indexOf(SPACE_GREATER_THAN_SYMBOL, spaceIndex),
                msg.indexOf(SPACE_BANG_SYMBOL, spaceIndex),
                msg.indexOf(SPACE_SLASH_SYMBOL, spaceIndex),
                msg.indexOf(SPACE_TILDE_SYMBOL, spaceIndex),
                msg.indexOf(SPACE_QUESTION_MARK_SYMBOL, spaceIndex));
        while (charIndex > 0) {
            indices.add(charIndex + 1);
            int index = charIndex + 2;
            charIndex = minNonNegative(msg.indexOf(SPACE_EQUALS_SYMBOL, index),
                    msg.indexOf(SPACE_LESS_THAN_SYMBOL, index),
                    msg.indexOf(SPACE_GREATER_THAN_SYMBOL, index),
                    msg.indexOf(SPACE_BANG_SYMBOL, index),
                    msg.indexOf(SPACE_SLASH_SYMBOL, index),
                    msg.indexOf(SPACE_TILDE_SYMBOL, index),
                    msg.indexOf(SPACE_QUESTION_MARK_SYMBOL, index));
        }
        indices.add(msg.length());
        List<ColumnProperties> cols = new ArrayList<ColumnProperties>();
        Query query = new Query(tps, tp);
        ColumnProperties drSlotColumn = null;
        int drSlotDuration = 0;
        for (int i = 0; i < indices.size() - 1; i++) {
            String token = msg.substring(indices.get(i), indices.get(i + 1));
            char c = token.charAt(0);
            spaceIndex = token.indexOf(SPACE_CHAR, 2);
            String value = (spaceIndex < 0) ? null :
                    token.substring(spaceIndex).trim();
            String colName = (spaceIndex < 0) ? token.substring(1).trim() :
                    token.substring(1, spaceIndex).trim();
            ColumnProperties cp = tp.getColumnByUserLabel(colName);
            if (cp == null) {
                return false;
            }
            if (c == '?') {
                cols.add(cp);
            } else if (c == '~') {
                if ((value != null) && value.startsWith("d")) {
                    query.setOrderBy(Query.SortOrder.DESCENDING, cp);
                } else {
                    query.setOrderBy(Query.SortOrder.ASCENDING, cp);
                }
            } else if (c == '/') {
                if ((drSlotColumn != null) || (value == null) ||
                    (cp.getColumnType() !=
                    ColumnType.DATE_RANGE)) {
                    return false;
                }
                drSlotColumn = cp;
                drSlotDuration = du.tryParseDuration(value);
                if (drSlotDuration < 0) {
                    return false;
                }
            } else {
                if (!addConstraint(query, cp, c, value)) {
                    return false;
                }
            }
        }
        if (drSlotColumn == null) {
            return respondToSimpleQuery(phoneNum, tp, cols, query);
        } else {
            return respondToDrSlotQuery(phoneNum, tp, query, drSlotColumn,
                    drSlotDuration);
        }
    }

    private boolean addConstraint(Query query, ColumnProperties cp,
            char comparator, String value) {
    	// TODO: do Time and DateTime get processed the same as Date???
        if ((cp.getColumnType() == ColumnType.DATE_RANGE) ||
                (cp.getColumnType() == ColumnType.DATE) ||
                (cp.getColumnType() == ColumnType.DATETIME) ||
                (cp.getColumnType() == ColumnType.TIME)) {
            Interval interval = du.tryParseInterval(value);
            if (interval == null) {
                DateTime dt = du.tryParseInstant(value);
                if (dt == null) {
                    return false;
                }
                String dbValue = du.formatDateTimeForDb(dt);
                if (comparator == '=') {
                    if (cp.getColumnType() ==
                        ColumnType.DATE_RANGE) {
                        return false;
                    }
                    query.addConstraint(cp, Query.Comparator.EQUALS, dbValue);
                } else if (comparator == '<') {
                    query.addConstraint(cp, Query.Comparator.LESS_THAN,
                            dbValue);
                } else if (comparator == '>') {
                    query.addConstraint(cp, Query.Comparator.GREATER_THAN,
                            dbValue);
                } else {
                    if (cp.getColumnType() ==
                        ColumnType.DATE_RANGE) {
                        return false;
                    }
                    query.addConstraint(cp, Query.Comparator.NOT_EQUALS,
                            dbValue);
                }
            } else {
                if (comparator == '=') {
                    if ((cp.getColumnType() ==
                        ColumnType.DATE) ||
                        (cp.getColumnType() ==
                        ColumnType.DATETIME) ||
                        (cp.getColumnType() ==
                        ColumnType.TIME)) {
                        query.addConstraint(cp,
                                Query.Comparator.GREATER_THAN_EQUALS,
                                du.formatDateTimeForDb(interval.getStart()));
                        query.addConstraint(cp, Query.Comparator.LESS_THAN,
                                du.formatDateTimeForDb(interval.getEnd()));
                    } else {
                        query.addConstraint(cp, Query.Comparator.EQUALS,
                                du.formatIntervalForDb(interval));
                    }
                } else if (comparator == '<') {
                    query.addConstraint(cp, Query.Comparator.LESS_THAN,
                            du.formatDateTimeForDb(interval.getStart()));
                } else if (comparator == '>') {
                    query.addConstraint(cp,
                            Query.Comparator.GREATER_THAN_EQUALS,
                            du.formatDateTimeForDb(interval.getEnd()));
                } else {
                    if ((cp.getColumnType() ==
                            ColumnType.DATE) ||
                            (cp.getColumnType() ==
                            ColumnType.DATETIME) ||
                            (cp.getColumnType() ==
                            ColumnType.TIME)) {
                        query.addOrConstraint(cp, Query.Comparator.LESS_THAN,
                                du.formatDateTimeForDb(interval.getStart()),
                                Query.Comparator.GREATER_THAN_EQUALS,
                                du.formatDateTimeForDb(interval.getEnd()));
                    } else {
                        query.addConstraint(cp, Query.Comparator.NOT_EQUALS,
                                du.formatIntervalForDb(interval));
                    }
                }
            }
        } else {
            value = du.validifyValue(cp, value);
            if (value == null) {
                return false;
            }
            if (comparator == '=') {
                query.addConstraint(cp, Query.Comparator.EQUALS, value);
            } else if (comparator == '<') {
                query.addConstraint(cp, Query.Comparator.LESS_THAN, value);
            } else if (comparator == '>') {
                query.addConstraint(cp, Query.Comparator.GREATER_THAN, value);
            } else {
                query.addConstraint(cp, Query.Comparator.NOT_EQUALS, value);
            }
        }
        return true;
    }

    private boolean respondToSimpleQuery(String phoneNum, TableProperties tp,
            List<ColumnProperties> cols, Query query) {
        if (cols.isEmpty()) {
            return false;
        }
        int count = 0;
        for ( ColumnProperties cp : cols ) {
          if ( cp.isPersisted() ) {
            ++count;
          }
        }
        String[] colNames = new String[count];
        count = -1;
        for ( ColumnProperties cp : cols ) {
          if ( cp.isPersisted() ) {
            colNames[++count] = cp.getElementKey();
          }
        }
        DbTable dbt = DbTable.getDbTable(dbh, tp);
        UserTable table = dbt.getRaw(query, colNames);
        String resp;
        if (table.getHeight() == 0) {
            resp = "No rows found.";
        } else {
            int limit = Math.min(DEFAULT_LIMIT, table.getHeight());
            List<String> rows = new ArrayList<String>();
            for (int i = 0; i < limit; i++) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < cols.size(); j++) {
                    String colName = cols.get(j).getSmsLabel();
                    if (colName == null) {
                        colName = cols.get(j).getDisplayName();
                    }
                    sb.append("," + colName + ":" + table.getData(i, j));
                }
                sb.deleteCharAt(0);
                rows.add(sb.toString());
            }
            StringBuilder sb = new StringBuilder(rows.get(0));
            for (int i = 1; i < rows.size(); i++) {
                sb.append(";" + rows.get(i));
            }
            resp = sb.toString();
        }
        smsSender.sendSMSWithCutoff(phoneNum, resp);
        return true;
    }

    private boolean respondToDrSlotQuery(String phoneNum, TableProperties tp,
            Query query, ColumnProperties drSlotColumn, int drSlotDuration) {
        Set<Constraint> constraints = new HashSet<Constraint>();
        for (int i = query.getConstraintCount(); i >= 0; i--) {
            Constraint c = query.getConstraint(i);
            if (c.getColumnDbName().equals(drSlotColumn.getElementKey())) {
                constraints.add(c);
                query.removeConstraint(i);
            }
        }
        query.setOrderBy(Query.SortOrder.ASCENDING, drSlotColumn);
        DbTable dbt = DbTable.getDbTable(dbh, tp);
        UserTable table = dbt.getRaw(query,
                new String[] {drSlotColumn.getElementKey()});
        // TODO: range should not be slash-separated but stored as two columns OR json in db...
        List<String[]> rawRanges = new ArrayList<String[]>();
        for (int i = 0; i < table.getHeight(); i++) {
            rawRanges.add(table.getData(i, 0).split("/"));
        }
        String earlyDate = null;
        String lateDate = null;
        for (int i = 0; i < query.getConstraintCount(); i++) {
            Constraint c = query.getConstraint(i);
            if (c.getComparisonCount() == 2) {
                String[] range;
                if (c.getComparator(0) == Query.Comparator.LESS_THAN) {
                    range = new String[] {c.getValue(0), c.getValue(1)};
                } else {
                    range = new String[] {c.getValue(1), c.getValue(0)};
                }
                boolean inserted = false;
                for (int j = 0; j < rawRanges.size(); j++) {
                    if (range[0].compareTo(rawRanges.get(j)[0]) < 0) {
                        rawRanges.add(j, range);
                        inserted = true;
                        break;
                    }
                }
                if (!inserted) {
                    rawRanges.add(range);
                }
            } else if (c.getComparator(0) == Query.Comparator.LESS_THAN) {
                if (lateDate == null) {
                    lateDate = c.getValue(0);
                } else if (c.getValue(0).compareTo(lateDate) < 0){
                    lateDate = c.getValue(0);
                }
            } else if (c.getComparator(0) == Query.Comparator.GREATER_THAN) {
                if (earlyDate == null) {
                    earlyDate = c.getValue(0);
                } else if (c.getValue(0).compareTo(earlyDate) > 0){
                    earlyDate = c.getValue(0);
                }
            }
        }
        if (earlyDate != null) {
            rawRanges.add(new String[] {null, earlyDate});
        }
        if (lateDate != null) {
            for (int j = 0; j < rawRanges.size(); j++) {
                if (lateDate.compareTo(rawRanges.get(j)[0]) < 0) {
                    rawRanges.add(j, new String[] {lateDate, null});
                    break;
                }
            }
        }
        if (rawRanges.isEmpty()) {
            String resp = "anytime";
            smsSender.sendSMSWithCutoff(phoneNum, resp);
            return true;
        }
        List<String[]> ranges = new ArrayList<String[]>();
        ranges.add(rawRanges.get(0));
        for (int i = 1; i < rawRanges.size(); i++) {
            String[] lastRange = ranges.get(ranges.size() - 1);
            String[] nextRange = rawRanges.get(i);
            if (nextRange[1] == null) {
                ranges.add(nextRange);
                break;
            }
            if (nextRange[0].compareTo(lastRange[1]) > 0) {
                ranges.add(nextRange);
            } else if (nextRange[1].compareTo(lastRange[1]) > 0) {
                lastRange[1] = nextRange[1];
            }
        }
        StringBuilder sb = new StringBuilder();
        if (ranges.get(0)[0] != null) {
            DateTime dt = du.parseDateTimeFromDb(ranges.get(0)[0]);
            sb.append(";before" + du.formatShortDateTimeForUser(dt));
        }
        for (int i = 1; i < ranges.size(); i++) {
            DateTime start = du.parseDateTimeFromDb(ranges.get(i - 1)[1]);
            DateTime end = du.parseDateTimeFromDb(ranges.get(i)[0]);
            Duration duration = new Duration(start, end);
            if (duration.getStandardSeconds() >= drSlotDuration) {
                sb.append(";" + du.formatShortDateTimeForUser(start) +
                        "-" + du.formatShortDateTimeForUser(end));
            }
        }
        if (ranges.get(ranges.size() - 1)[1] != null) {
            DateTime dt = du.parseDateTimeFromDb(
                    ranges.get(ranges.size() - 1)[1]);
            sb.append(";after" + du.formatShortDateTimeForUser(dt));
        }
        sb.deleteCharAt(0);
        String resp = sb.toString();
        smsSender.sendSMSWithCutoff(phoneNum, resp);
        return true;
    }

    private int minNonNegative(int...vals) {
        int min = -1;
        for (int val : vals) {
            if (val >= 0) {
                min = (min == -1) ? val : Math.min(min, val);
            }
        }
        return min;
    }
}