/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command.dml;

import org.h2.expression.Expression;

/**
 * Describes one element of the ORDER BY clause of a query.
 */
public class SelectOrderBy {

    /**
     * The order by expression.
     */
    public Expression expression;

    /**
     * The column index expression. This can be a column index number (1 meaning
     * the first column of the select list) or a parameter (the parameter is a
     * number representing the column index number).
     */
    public Expression columnIndexExpr; //order by字段在select字段列表中的位置索引(从1开始计数)

    /**
     * If the column should be sorted descending.
     */
    public boolean descending;

    /**
     * If NULL should be appear first.
     */
    public boolean nullsFirst;

    /**
     * If NULL should be appear at the end.
     */
    public boolean nullsLast;

    public String getSQL() {
        StringBuilder buff = new StringBuilder();
        if (expression != null) {
            buff.append('=').append(expression.getSQL());
        } else {
            buff.append(columnIndexExpr.getSQL());
        }
        if (descending) {
            buff.append(" DESC");
        }
        if (nullsFirst) {
            buff.append(" NULLS FIRST");
        } else if (nullsLast) {
            buff.append(" NULLS LAST");
        }
        return buff.toString();
    }

}
