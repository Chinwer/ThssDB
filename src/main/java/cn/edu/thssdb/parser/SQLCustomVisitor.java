package cn.edu.thssdb.parser;

import cn.edu.thssdb.exception.ColumnNotExistException;
import cn.edu.thssdb.exception.ValueException;
import cn.edu.thssdb.query.*;
import cn.edu.thssdb.schema.Column;
import cn.edu.thssdb.schema.Manager;
import cn.edu.thssdb.type.ColumnType;
import javafx.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;

public class SQLCustomVisitor extends SQLBaseVisitor {
    private Manager manager;

    public SQLCustomVisitor(Manager manager) {
        super();
        this.manager = manager;
    }

    @Override
    public SQLEvalResult visitParse(SQLParser.ParseContext ctx) {
        return visitSql_stmt_list(ctx.sql_stmt_list());
    }

    @Override
    public SQLEvalResult visitSql_stmt_list(SQLParser.Sql_stmt_listContext ctx) {
        SQLEvalResult result = new SQLEvalResult();
        for (SQLParser.Sql_stmtContext c : ctx.sql_stmt()) {
            visitSql_stmt(c, result);
        }
        return result;
    }


    public void visitSql_stmt(SQLParser.Sql_stmtContext ctx, SQLEvalResult result) {
        if (ctx.create_db_stmt() != null) {
            String msg = visitCreate_db_stmt(ctx.create_db_stmt());
            result.setMessage(msg);
        } else if (ctx.drop_db_stmt() != null) {
            String msg = visitDrop_db_stmt(ctx.drop_db_stmt());
            result.setMessage(msg);
        } else if (ctx.use_db_stmt() != null) {
            String msg = visitUse_db_stmt(ctx.use_db_stmt());
            result.setMessage(msg);
        } else if (ctx.create_table_stmt() != null) {
            String msg = visitCreate_table_stmt(ctx.create_table_stmt());
            result.setMessage(msg);
        } else if (ctx.drop_table_stmt() != null) {
            String msg = visitDrop_table_stmt(ctx.drop_table_stmt());
            result.setMessage(msg);
        } else if (ctx.insert_stmt() != null) {
            String msg = visitInsert_stmt(ctx.insert_stmt());
            result.setMessage(msg);
        } else if (ctx.select_stmt() != null) {
            QueryResult queryResult = visitSelect_stmt(ctx.select_stmt());
            result.setQueryResult(queryResult);
        }
    }

    @Override
    public String visitCreate_db_stmt(SQLParser.Create_db_stmtContext ctx) {
        String dbName = ctx.database_name().getText();
        manager.createDatabase(dbName.toLowerCase());
        return String.format("Created database '%s'", dbName);
    }

    @Override
    public String visitDrop_db_stmt(SQLParser.Drop_db_stmtContext ctx) {
        String dbName = ctx.database_name().getText();
        manager.deleteDatabase(dbName.toLowerCase());
        return String.format("Dropped database '%s'", dbName);
    }

    @Override
    public String visitUse_db_stmt(SQLParser.Use_db_stmtContext ctx) {
        String dbName = ctx.database_name().getText();
        manager.switchDatabase(dbName);
        return String.format("Switched to database '%s'", dbName);
    }

    private boolean equals(String columnName1, String columnName2) {
        return columnName1.toLowerCase().equals(columnName2.toLowerCase());
    }

    @Override
    public String visitCreate_table_stmt(SQLParser.Create_table_stmtContext ctx) {
        String tbName = ctx.table_name().getText();
        int n = ctx.column_def().size();

        // resolve columns definition
        Column[] columns = new Column[n];
        for (int i = 0; i < n; i++) {
            columns[i] = visitColumn_def(ctx.column_def().get(i));
        }

        // resolve table constraints
        if (ctx.table_constraint() != null) {
            String[] primaryNames = visitTable_constraint(ctx.table_constraint());
            int length = primaryNames.length;
            if (length >= 1) {
                for (String name : primaryNames) {
                    boolean exist = false;
                    for (Column column : columns) {
                        if (equals(column.getName(), name)) {
                            exist = true;
                            column.setPrimary(length == 1 ? 1 : 2);
                        }
                    }
                    if (!exist) {
                        throw new ColumnNotExistException(name);
                    }
                }
            }
        }

        try {
            manager.createTable(tbName, columns);
        } catch (Exception e) {
            return e.getMessage();
        }
        return "Created table " + tbName + ".";
    }

    @Override
    public String visitDrop_table_stmt(SQLParser.Drop_table_stmtContext ctx) {
        String tbName = ctx.table_name().getText();
        manager.deleteTable(tbName, ctx.K_IF() != null);
        return String.format("Dropped table '%s'", tbName);
    }

    @Override
    public String visitInsert_stmt(SQLParser.Insert_stmtContext ctx) {
        String tableName = ctx.table_name().getText().toLowerCase();
        String[] columnNames = null;
        if (ctx.column_name() != null && ctx.column_name().size() != 0) {
            columnNames = new String[ctx.column_name().size()];
            for (int i = 0; i < ctx.column_name().size(); i++) {
                columnNames[i] = ctx.column_name(i).getText().toLowerCase();
            }
        }
        for (SQLParser.Value_entryContext subCtx : ctx.value_entry()) {
            String[] values = visitValue_entry(subCtx);
            manager.insert(tableName, values, columnNames);
        }
        return "Inserted " + ctx.value_entry().size() + " rows.";
    }

    @Override
    public String[] visitValue_entry(SQLParser.Value_entryContext ctx) {
        String[] values = new String[ctx.literal_value().size()];
        for (int i = 0; i < ctx.literal_value().size(); i++) {
            values[i] = ctx.literal_value(i).getText();
        }
        return values;
    }

    @Override
    public QueryResult visitSelect_stmt(SQLParser.Select_stmtContext ctx) {
        boolean distinct = false;
        if (ctx.K_DISTINCT() != null) {
            distinct = true;
        }
        int nColumn = ctx.result_column().size();
        String[] columnNames = new String[nColumn];
        for (int i = 0; i < nColumn; i++) {
            String columnName = ctx.result_column(i).getText().toLowerCase();
            if (columnName.equals("*")) {
                columnNames = null;
                break;
            }
            columnNames[i] = columnName;
        }
        int count = ctx.table_query().size();
        QueryTable[] queryTables = new QueryTable[count];
        for (int i = 0; i < count; i++) {
            queryTables[i] = visitTable_query(ctx.table_query(i));
        }
        Where where = null;
        if (ctx.K_WHERE() != null) {
            where = visitMultiple_condition(ctx.multiple_condition());
        }
        return manager.select(columnNames, queryTables, where, distinct);
    }

    @Override
    public Where visitMultiple_condition(SQLParser.Multiple_conditionContext ctx) {
        if (ctx.condition() != null) {
            Cond cond = visitCondition(ctx.condition());
            return new Where(cond);
        } else {
            Where left, right;
            left = visitMultiple_condition(ctx.multiple_condition(0));
            right = visitMultiple_condition(ctx.multiple_condition(1));
            if (ctx.AND() != null) {
                return new Where(left, right, Where.Op.AND);
            } else {
                return new Where(left, right, Where.Op.OR);
            }
        }
    }

    @Override
    public Cond visitCondition(SQLParser.ConditionContext ctx) {
        Expr left = visitExpression(ctx.expression(0));
        Expr right = visitExpression(ctx.expression(1));
        Cond.Op op = visitComparator(ctx.comparator());
        return new Cond(left, right, op);
    }

    @Override
    public Expr visitExpression(SQLParser.ExpressionContext ctx) {
        if (ctx.comparer() != null) {
            return new Expr(visitComparer(ctx.comparer()));
        } else if (ctx.expression().size() == 1) {
            return visitExpression(ctx.expression(0));
        } else {
            Expr left = visitExpression(ctx.expression(0));
            Expr right = visitExpression(ctx.expression(1));
            Expr.Op op;
            if (ctx.ADD() != null) {
                op = Expr.Op.ADD;
            } else if (ctx.SUB() != null) {
                op = Expr.Op.SUB;
            } else if (ctx.MUL() != null) {
                op = Expr.Op.MUL;
            } else if (ctx.DIV() != null) {
                op = Expr.Op.DIV;
            } else {
                op = null;
            }
            return new Expr(left, right, op);
        }
    }

    @Override
    public Value visitComparer(SQLParser.ComparerContext ctx) {
        if (ctx.column_full_name() != null) {
            return new Value(ctx.column_full_name().getText().toLowerCase(), Value.Type.COLUMN);
        } else {
            return visitLiteral_value(ctx.literal_value());
        }
    }

    @Override
    public Value visitLiteral_value(SQLParser.Literal_valueContext ctx) {
        if (ctx.NUMERIC_LITERAL() != null) {
            return new Value(ctx.NUMERIC_LITERAL().getText(), Value.Type.NUMBER);
        } else if (ctx.K_NULL() != null) {
            return new Value(null, Value.Type.NULL);
        } else {
            return new Value(ctx.STRING_LITERAL().getText(), Value.Type.STRING);
        }
    }

    @Override
    public Cond.Op visitComparator(SQLParser.ComparatorContext ctx) {
        if (ctx.EQ() != null) {
            return Cond.Op.EQ;
        } else if (ctx.NE() != null) {
            return Cond.Op.NE;
        } else if (ctx.GT() != null) {
            return Cond.Op.GT;
        } else if (ctx.GE() != null) {
            return Cond.Op.GE;
        } else if (ctx.LE() != null) {
            return Cond.Op.LE;
        } else if (ctx.LT() != null) {
            return Cond.Op.LT;
        }
        return null;
    }

    @Override
    public QueryTable visitTable_query(SQLParser.Table_queryContext ctx) {
        if (ctx.K_JOIN().size() == 0) {
            return manager.getSingleTable(ctx.table_name(0).getText().toLowerCase());
        }
        Where join = visitMultiple_condition(ctx.multiple_condition());
        List<String> tableNames = ctx
                .table_name()
                .stream()
                .map(sCtx -> sCtx.getText().toLowerCase())
                .collect(Collectors.toList());
        return manager.getJointTable(tableNames, join);
    }

    @Override
    public Column visitColumn_def(SQLParser.Column_defContext ctx) {
        boolean nonNull = false;
        int primary = 0;
        for (SQLParser.Column_constraintContext column_cons : ctx.column_constraint()) {
            String type = visitColumn_constraint(column_cons);
            if (type.equals("PRIMARY")) {
                primary = 1;
            } else if (type.equals("NONNULL")) {
                nonNull = true;
            }
            nonNull = nonNull || (primary > 0);
        }
        String name = ctx.column_name().getText().toLowerCase();
        Pair<ColumnType, Integer> type = visitType_name(ctx.type_name());
        ColumnType columnType = type.getKey();
        int maxLength = type.getValue();
        return new Column(name, columnType, primary, nonNull, maxLength);
    }

    @Override
    public String visitColumn_constraint(SQLParser.Column_constraintContext ctx) {
        if (ctx.K_PRIMARY() != null) {
            return "PRIMARY";
        } else if (ctx.K_NULL() != null) {
            return "NONNULL";
        }
        return null;
    }

    @Override
    public String[] visitTable_constraint(SQLParser.Table_constraintContext ctx) {
        int nColumn = ctx.column_name().size();
        String[] names = new String[nColumn];

        for (int i = 0; i < nColumn; i++) {
            names[i] = ctx.column_name(i).getText().toLowerCase();
        }
        return names;
    }

    @Override
    public Pair<ColumnType, Integer> visitType_name(SQLParser.Type_nameContext ctx) {
        if (ctx.T_INT() != null) {
            return new Pair<>(ColumnType.INT, -1);
        }
        if (ctx.T_LONG() != null) {
            return new Pair<>(ColumnType.LONG, -1);
        }
        if (ctx.T_FLOAT() != null) {
            return new Pair<>(ColumnType.FLOAT, -1);
        }
        if (ctx.T_DOUBLE() != null) {
            return new Pair<>(ColumnType.DOUBLE, -1);
        }
        if (ctx.T_STRING() != null) {
            return new Pair<>(ColumnType.STRING, Integer.parseInt(ctx.NUMERIC_LITERAL().getText()));
        }
        return null;
    }
}