package liquibase.sqlgenerator.core;

import liquibase.Scope;
import liquibase.database.Database;
import liquibase.database.core.*;
import liquibase.datatype.DatabaseDataType;
import liquibase.exception.DatabaseException;
import liquibase.exception.ValidationErrors;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.*;
import liquibase.statement.core.CreateTableStatement;
import liquibase.structure.core.Catalog;
import liquibase.structure.core.ForeignKey;
import liquibase.structure.core.Relation;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Sequence;
import liquibase.structure.core.Table;
import liquibase.util.StringUtil;
import org.apache.commons.lang3.StringUtils;

import java.math.BigInteger;
import java.util.*;

public class CreateTableGenerator extends AbstractSqlGenerator<CreateTableStatement> {

    @Override
    public ValidationErrors validate(CreateTableStatement createTableStatement, Database database, SqlGeneratorChain sqlGeneratorChain) {
        ValidationErrors validationErrors = new ValidationErrors();
        validationErrors.checkRequiredField("tableName", createTableStatement.getTableName());
        validationErrors.checkRequiredField("columns", createTableStatement.getColumns());

        if (createTableStatement.getAutoIncrementConstraints() != null) {
            for (AutoIncrementConstraint constraint : createTableStatement.getAutoIncrementConstraints()) {
                validationErrors.checkDisallowedField("incrementBy", constraint.getIncrementBy(), database, MySQLDatabase.class);
            }
        }
        return validationErrors;
    }

    @Override
    public Sql[] generateSql(CreateTableStatement statement, Database database, SqlGeneratorChain sqlGeneratorChain) {

        List<Sql> additionalSql = new ArrayList<>();

        StringBuilder buffer = new StringBuilder();
        buffer.append("CREATE ");

        if (StringUtils.isNotEmpty(statement.getTableType())) {
            buffer.append(statement.getTableType().trim().toUpperCase()).append(" ");
        }
        buffer.append("TABLE ");

        if (statement.isIfNotExists() && database.supportsCreateIfNotExists(Table.class)) {
            buffer.append("IF NOT EXISTS ");
        }

        buffer.append(generateTableName(database, statement)).append(" ");
        buffer.append("(");

        boolean isSinglePrimaryKeyColumn = (statement.getPrimaryKeyConstraint() != null) && (statement
                .getPrimaryKeyConstraint().getColumns().size() == 1);

        boolean isPrimaryKeyAutoIncrement = false;

        Iterator<String> columnIterator = statement.getColumns().iterator();

        BigInteger mysqlTableOptionStartWith = null;
        List<String> autoIncrementColumns = new ArrayList<>();

        /* We have reached the point after "CREATE TABLE ... (" and will now iterate through the column list. */
        while (columnIterator.hasNext()) {
            String column = columnIterator.next();
            DatabaseDataType columnType = null;
            if (statement.getColumnTypes().get(column) != null) {
                columnType = statement.getColumnTypes().get(column).toDatabaseDataType(database);
            }

            if (columnType == null) {
                buffer.append(database.escapeColumnName(statement.getCatalogName(), statement.getSchemaName(), statement.getTableName(), column, false));
            } else {
                buffer.append(database.escapeColumnName(statement.getCatalogName(), statement.getSchemaName(), statement.getTableName(), column, !statement.isComputed(column)));
                buffer.append(" ").append(columnType);
            }


            AutoIncrementConstraint autoIncrementConstraint = null;

            for (AutoIncrementConstraint currentAutoIncrementConstraint : statement.getAutoIncrementConstraints()) {
                if (column.equals(currentAutoIncrementConstraint.getColumnName())) {
                    autoIncrementConstraint = currentAutoIncrementConstraint;
                    break;
                }
            }

            boolean isAutoIncrementColumn = autoIncrementConstraint != null;
            if (isAutoIncrementColumn) {
                autoIncrementColumns.add(column);
            }
            boolean isPrimaryKeyColumn = (statement.getPrimaryKeyConstraint() != null) && statement
                    .getPrimaryKeyConstraint().getColumns().contains(column);
            isPrimaryKeyAutoIncrement = isPrimaryKeyAutoIncrement || (isPrimaryKeyColumn && isAutoIncrementColumn);

            if ((database instanceof SQLiteDatabase) &&
                    isSinglePrimaryKeyColumn &&
                    isPrimaryKeyColumn &&
                    isAutoIncrementColumn) {
                String pkName = StringUtils.trimToNull(statement.getPrimaryKeyConstraint().getConstraintName());
                if (pkName == null) {
                    pkName = database.generatePrimaryKeyName(statement.getTableName());
                }
                if (pkName != null) {
                    buffer.append(" CONSTRAINT ");
                    buffer.append(database.escapeConstraintName(pkName));
                }
                buffer.append(" PRIMARY KEY");
            }

            // for the serial data type in postgres, there should be no default value
            if (columnType != null && !columnType.isAutoIncrement() && (statement.getDefaultValue(column) != null)) {
                handleDefaultValue(statement, database, column, buffer);
            }

            if (isAutoIncrementColumn) {
                if (database instanceof PostgresDatabase && buffer.toString().toLowerCase().endsWith("serial")) {
                    //don't add more info
                } else if (database.supportsAutoIncrement()) {
                    // TODO: check if database supports auto increment on non primary key column
                    String autoIncrementClause = database.getAutoIncrementClause(autoIncrementConstraint.getStartWith(), autoIncrementConstraint.getIncrementBy(), autoIncrementConstraint.getGenerationType(), autoIncrementConstraint.getDefaultOnNull());

                    if (!autoIncrementClause.isEmpty()) {
                        buffer.append(" ").append(autoIncrementClause);
                    }

                    if (autoIncrementConstraint.getStartWith() != null) {
                        if (database instanceof PostgresDatabase) {
                            int majorVersion = 9;
                            try {
                                majorVersion = database.getDatabaseMajorVersion();
                            } catch (DatabaseException e) {
                                // ignore
                            }
                            if (majorVersion < 10) {
                                String sequenceName = statement.getTableName() + "_" + column + "_seq";
                                additionalSql.add(new UnparsedSql("alter sequence " + database.escapeSequenceName(statement.getCatalogName(), statement.getSchemaName(), sequenceName) + " start with " + autoIncrementConstraint.getStartWith(), new Sequence().setName(sequenceName).setSchema(statement.getCatalogName(), statement.getSchemaName())));
                            }
                        } else if (database instanceof MySQLDatabase) {
                            mysqlTableOptionStartWith = autoIncrementConstraint.getStartWith();
                        }
                    }
                } else {
                    Scope.getCurrentScope().getLog(getClass()).warning(database.getShortName() + " does not support autoincrement columns as requested for " + (database.escapeTableName(statement.getCatalogName(), statement.getSchemaName(), statement.getTableName())));
                }
            }

            // Do we have a NOT NULL constraint for this column?
            if (statement.getNotNullColumns().get(column) != null) {
                if (!database.supportsNotNullConstraintNames()) {
                    buffer.append(" NOT NULL");
                } else {
                    /* Determine if the NOT NULL constraint has a name. */
                    NotNullConstraint nnConstraintForThisColumn = statement.getNotNullColumns().get(column);
                    String nncName = StringUtils.trimToNull(nnConstraintForThisColumn.getConstraintName());
                    if (nncName == null) {
                        buffer.append(" NOT NULL");
                    } else {
                        buffer.append(" CONSTRAINT ");
                        buffer.append(database.escapeConstraintName(nncName));
                        buffer.append(" NOT NULL");
                    }

                    if (!nnConstraintForThisColumn.shouldValidateNullable()) {
                        if (database instanceof OracleDatabase) {
                            buffer.append(" ENABLE NOVALIDATE ");
                        }
                    }
                } // does the DB support constraint names?
            } else {
                if (columnType != null && ((database instanceof SybaseDatabase) || (database instanceof SybaseASADatabase) || (database
                        instanceof MySQLDatabase) || ((database instanceof MSSQLDatabase) && columnType.toString()
                        .toLowerCase().contains("timestamp")))) {
                    buffer.append(" NULL");
                } // Do we need to specify NULL explicitly?
            } // Do we have a NOT NULL constraint for this column?

            if ((database instanceof MySQLDatabase) && (statement.getColumnRemarks(column) != null)) {
                buffer.append(" COMMENT '" + database.escapeStringForDatabase(statement.getColumnRemarks(column)) + "'");
            }

            if (columnIterator.hasNext()) {
                buffer.append(", ");
            }
        }

        buffer.append(",");

        if (!((database instanceof SQLiteDatabase) &&
                isSinglePrimaryKeyColumn &&
                isPrimaryKeyAutoIncrement)) {

            if ((statement.getPrimaryKeyConstraint() != null) && !statement.getPrimaryKeyConstraint().getColumns()
                    .isEmpty()) {
                if (database.supportsPrimaryKeyNames()) {
                    String pkName = StringUtils.trimToNull(statement.getPrimaryKeyConstraint().getConstraintName());
                    if (pkName == null) {
                        // TODO ORA-00972: identifier is too long
                        // If tableName lenght is more then 28 symbols
                        // then generated pkName will be incorrect
                        pkName = database.generatePrimaryKeyName(statement.getTableName());
                    }
                    if (pkName != null) {
                        buffer.append(" CONSTRAINT ");
                        buffer.append(database.escapeConstraintName(pkName));
                    }
                }
                buffer.append(" PRIMARY KEY (");
                buffer.append(database.escapeColumnNameList(StringUtil.join(getPrimaryKeyColumns(statement.getPrimaryKeyConstraint().getColumns(), database, autoIncrementColumns), ", ")));
                buffer.append(")");
                // Setting up table space for PK's index if it exists
                if (((database instanceof OracleDatabase) || (database instanceof PostgresDatabase)) && (StringUtils.isNotEmpty(statement
                        .getPrimaryKeyConstraint().getTablespace()))) {
                    buffer.append(" USING INDEX TABLESPACE ");
                    buffer.append(statement.getPrimaryKeyConstraint().getTablespace());
                }
                buffer.append(!statement.getPrimaryKeyConstraint().shouldValidatePrimaryKey() ? " ENABLE NOVALIDATE " : "");

                if (database.supportsInitiallyDeferrableColumns() && !(database instanceof SybaseASADatabase)) {
                    if (statement.getPrimaryKeyConstraint().isInitiallyDeferred()) {
                        buffer.append(" INITIALLY DEFERRED");
                    }
                    if (statement.getPrimaryKeyConstraint().isDeferrable()) {
                        buffer.append(" DEFERRABLE");
                    }
                }

                buffer.append(",");
            }
        }

        for (ForeignKeyConstraint fkConstraint : statement.getForeignKeyConstraints()) {
            if (!(database instanceof InformixDatabase)) {
                buffer.append(" CONSTRAINT ");
                buffer.append(database.escapeConstraintName(fkConstraint.getForeignKeyName()));
            }
            String referencesString = fkConstraint.getReferences();

            buffer.append(" FOREIGN KEY (")
                    .append(database.escapeColumnName(statement.getCatalogName(), statement.getSchemaName(), statement.getTableName(), fkConstraint.getColumn()))
                    .append(") REFERENCES ");
            if (referencesString != null) {
                if (!referencesString.contains(".") && (database.getDefaultSchemaName() != null) && database
                        .getOutputDefaultSchema() && (database.supports(Schema.class) || database.supports(Catalog.class))) {
                    referencesString = database.escapeObjectName(database.getDefaultSchemaName(), Schema.class) + "." + referencesString;
                }
                buffer.append(referencesString);
            } else {
                buffer.append(database.escapeObjectName(fkConstraint.getReferencedTableCatalogName(), fkConstraint.getReferencedTableSchemaName(), fkConstraint.getReferencedTableName(), Table.class))
                        .append("(")
                        .append(database.escapeColumnNameList(fkConstraint.getReferencedColumnNames()))
                        .append(")");

            }


            if (fkConstraint.isDeleteCascade()) {
                buffer.append(" ON DELETE CASCADE");
            }

            if ((database instanceof InformixDatabase)) {
                buffer.append(" CONSTRAINT ");
                buffer.append(database.escapeConstraintName(fkConstraint.getForeignKeyName()));
            }

            if (fkConstraint.isInitiallyDeferred()) {
                if (database instanceof SybaseASADatabase) {
                    buffer.append(" CHECK ON COMMIT");
                } else {
                    buffer.append(" INITIALLY DEFERRED");
                }
            }
            if (fkConstraint.isDeferrable() && !(database instanceof SybaseASADatabase)) {
                buffer.append(" DEFERRABLE");
            }

            if (database instanceof OracleDatabase) {
                buffer.append(!fkConstraint.shouldValidateForeignKey() ? " ENABLE NOVALIDATE " : "");
            }

            buffer.append(",");
        }

        /*
         * In the current syntax, UNIQUE constraints can only be set per column on table creation.
         * To alleviate this problem we combine the columns of unique constraints that have the same name.
         */
        LinkedHashMap<String, UniqueConstraint> namedUniqueConstraints = new LinkedHashMap<>();
        List<UniqueConstraint> unnamedUniqueConstraints = new LinkedList<>();
        for (UniqueConstraint uniqueConstraint : statement.getUniqueConstraints()) {
            if (uniqueConstraint.getConstraintName() == null) {  // Only combine uniqueConstraints that have a name.
                unnamedUniqueConstraints.add(uniqueConstraint);
            } else {
                String constraintName = uniqueConstraint.getConstraintName();
                UniqueConstraint existingConstraint = namedUniqueConstraints.get(constraintName);

                if (existingConstraint != null) {
                    if (uniqueConstraint.shouldValidateUnique()) {
                        //if validateUnique = true on only one column, make sure it is true
                        existingConstraint.setValidateUnique(true);
                    }

                    existingConstraint.getColumns().addAll(uniqueConstraint.getColumns());
                } else {
                    // if we haven't seen the constraint before put it in the map.
                    namedUniqueConstraints.put(constraintName, uniqueConstraint);
                }
            }
        }

        unnamedUniqueConstraints.addAll(namedUniqueConstraints.values());

        for (UniqueConstraint uniqueConstraint : unnamedUniqueConstraints) {
            if (uniqueConstraint.getConstraintName() != null) {
                buffer.append(" CONSTRAINT ");
                buffer.append(database.escapeConstraintName(uniqueConstraint.getConstraintName()));
            }
            buffer.append(" UNIQUE (");
            buffer.append(database.escapeColumnNameList(StringUtil.join(uniqueConstraint.getColumns(), ", ")));
            buffer.append(")");
            if (database instanceof OracleDatabase) {
                buffer.append(!uniqueConstraint.shouldValidateUnique() ? " ENABLE NOVALIDATE " : "");
            }
            buffer.append(",");
        }

        /*
         * Here, the list of columns and constraints in the form
         * ( column1, ..., columnN, constraint1, ..., constraintN,
         * ends. We cannot leave an expression like ", )", so we remove the last comma.
         */
        String sql = buffer.toString().replaceFirst(",\\s*$", "") + ")";

        if ((database instanceof MySQLDatabase) && (mysqlTableOptionStartWith != null)) {
            Scope.getCurrentScope().getLog(getClass()).info("[MySQL] Using last startWith statement ("+ mysqlTableOptionStartWith +") as table option.");
            sql += " " + ((MySQLDatabase) database).getTableOptionAutoIncrementStartWithClause(mysqlTableOptionStartWith);
        }

        if ((statement.getTablespace() != null) && database.supportsTablespaces()) {
            if ((database instanceof MSSQLDatabase) || (database instanceof SybaseASADatabase)) {
                sql += " ON " + statement.getTablespace();
            } else if ((database instanceof AbstractDb2Database) || (database instanceof InformixDatabase)) {
                sql += " IN " + statement.getTablespace();
            } else {
                sql += " TABLESPACE " + statement.getTablespace();
            }
        }

        if ((database instanceof MySQLDatabase) && (statement.getRemarks() != null)) {
            sql += " COMMENT='" + database.escapeStringForDatabase(statement.getRemarks()) + "' ";
        }

        if (database instanceof OracleDatabase && statement.isRowDependencies()) {
            sql += " ROWDEPENDENCIES ";
        }

        additionalSql.add(0, new UnparsedSql(sql, getAffectedTable(statement)));
        return additionalSql.toArray(EMPTY_SQL);
    }

    private void handleDefaultValue(CreateTableStatement statement, Database database, String column, StringBuilder buffer) {
        Object defaultValue = statement.getDefaultValue(column);
        if (database instanceof MSSQLDatabase) {
            handleMsSqlConstraintForDefaultValue(statement, database, column, buffer);
        }
        if (((database instanceof OracleDatabase) || (database instanceof PostgresDatabase) || database.getShortName().equalsIgnoreCase("databricks"))
                && statement.getDefaultValue(column).toString().startsWith("GENERATED ALWAYS ")) {
            buffer.append(" ");
        } else if (database instanceof HsqlDatabase && statement.getDefaultValue(column) instanceof SequenceNextValueFunction) {
            buffer.append(" ");
        } else if (database instanceof Db2zDatabase && statement.getDefaultValue(column).toString().contains("CURRENT TIMESTAMP")
                || statement.getDefaultValue(column).toString().contains("IDENTITY GENERATED BY DEFAULT")) {
            buffer.append(" ");
        } else if (database instanceof SybaseASADatabase && defaultValue.toString().startsWith("COMPUTE")) {
            buffer.append(' ');
        } else {
            buffer.append(" DEFAULT ");
        }

        if (defaultValue instanceof DatabaseFunction) {
            buffer.append(database.generateDatabaseFunctionValue((DatabaseFunction) defaultValue));
        } else if (database instanceof Db2zDatabase) {
            handleDb2zDefaultValue(statement, column, buffer);
        } else {
            buffer.append(statement.getColumnTypes().get(column).objectToSql(defaultValue, database));
        }
    }

    private void handleMsSqlConstraintForDefaultValue(CreateTableStatement statement, Database database, String column, StringBuilder buffer) {
        String constraintName = statement.getDefaultValueConstraintName(column);
        if (constraintName == null) {
            constraintName = ((MSSQLDatabase) database).generateDefaultConstraintName(statement.getTableName(), column);
        }
        buffer.append(" CONSTRAINT ").append(database.escapeObjectName(constraintName, ForeignKey.class));
    }

    private void handleDb2zDefaultValue(CreateTableStatement statement, String column, StringBuilder buffer) {
        if (statement.getDefaultValue(column).toString().contains("IDENTITY GENERATED BY DEFAULT")) {
            buffer.append("GENERATED BY DEFAULT AS IDENTITY");
        }
        if (statement.getDefaultValue(column).toString().contains("CURRENT USER")) {
            buffer.append("SESSION_USER ");
        }
        if (statement.getDefaultValue(column).toString().contains("CURRENT SQLID")) {
            buffer.append("CURRENT SQLID ");
        }
    }

    private String generateTableName(Database database, CreateTableStatement statement) {
        // In Postgresql, temp tables get their own schema and each session (connection) gets
        //its own temp schema. So - don't qualify them by schema.
        if (!(database instanceof PostgresDatabase) || StringUtils.isEmpty(statement.getTableType()) || !statement.getTableType().trim().toLowerCase().contains("temp")) {
            return database.escapeTableName(statement.getCatalogName(), statement.getSchemaName(), statement.getTableName());
        } else {
            return database.escapeObjectName(statement.getTableName(), Table.class);
        }
    }

    /**
     * Given the list of primary key columns, return that same list in the order that the database platform expects
     * based on the order of the auto increment columns.
     * @param primaryKeyColumns the primary key columns in the create table statement
     * @param autoIncrementColumns a list of the columns (in order) that are specified as auto increment columns
     * @return the sorted list of primary keys
     */
    private List<String> getPrimaryKeyColumns(List<String> primaryKeyColumns, Database database, List<String> autoIncrementColumns) {
        // MySQL requires that the columns in the PK statement follow the same order as the auto-increment columns.
        if (database instanceof MySQLDatabase) {
            // Creating a copy of the list so that the list parameter is not mutated.
            List<String> pkColumnsCopy = new ArrayList<>(primaryKeyColumns);
            // Now sort the PKs based on the order of the auto increment columns.
            List<String> sortedPkColumns = new ArrayList<>(primaryKeyColumns.size());
            for (String autoIncrementColumn : autoIncrementColumns) {
                if (pkColumnsCopy.contains(autoIncrementColumn)) {
                    sortedPkColumns.add(autoIncrementColumn);
                }
            }

            // Remove all of the PKs that have been processed already.
            pkColumnsCopy.removeAll(sortedPkColumns);

            // Add whatever is left.
            sortedPkColumns.addAll(pkColumnsCopy);

            return sortedPkColumns;
        } else {
            return primaryKeyColumns;
        }
    }

    protected Relation getAffectedTable(CreateTableStatement statement) {
        return new Table().setName(statement.getTableName()).setSchema(new Schema(statement.getCatalogName(), statement.getSchemaName()));
    }

}
