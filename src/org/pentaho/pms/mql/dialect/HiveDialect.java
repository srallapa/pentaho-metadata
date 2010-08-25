package org.pentaho.pms.mql.dialect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.pentaho.metadata.messages.Messages;
import org.pentaho.pms.mql.dialect.SQLQueryModel.SQLSelection;
import org.pentaho.pms.mql.dialect.SQLQueryModel.SQLTable;
import org.pentaho.pms.mql.dialect.SQLQueryModel.SQLWhereFormula;
import org.pentaho.pms.util.Const;

/**
 * Apache Hadoop Hive Implementation of Metadata SQL Dialect
 * 
 * @author Jordan Ganoff (jganoff@pentaho.com)
 * 
 */
public class HiveDialect extends DefaultSQLDialect {

  /**
   * Hive only supports the equals operator in join conditions.  This pattern is used to detect the existence of invalid
   * operators in a join condition.
   */
  private final Pattern INVALID_JOIN_OPERATORS = Pattern.compile("[!]|[>]|[<]|is null|is not null"); //$NON-NLS-1$

  public HiveDialect() {
    super("HIVE"); //$NON-NLS-1$
  }

  /**
   * Sorts {@link SQLJoin}s by the natural order of their {@link SQLJoin#joinOrderKey}s.
   */
  private static class InnerJoinComparator implements Comparator<SQLJoin> {
    private static InnerJoinComparator INSTANCE = new InnerJoinComparator();

    private InnerJoinComparator() {
    }

    public static InnerJoinComparator getInstance() {
      return INSTANCE;
    }

    public int compare(SQLJoin left, SQLJoin right) {
      // Case: no join order / no join order => equal
      if (Const.isEmpty(left.getJoinOrderKey()) && Const.isEmpty(right.getJoinOrderKey())) {
        return 0;
      }
      // Case:  join order / no join order => join order comes first
      if (!Const.isEmpty(left.getJoinOrderKey()) && Const.isEmpty(right.getJoinOrderKey())) {
        return -1;
      }
      // Case: no join order / join order => join order comes first
      if (Const.isEmpty(left.getJoinOrderKey()) && !Const.isEmpty(right.getJoinOrderKey())) {
        return 1;
      }
      // Case: join order / join order => natural order
      return left.getJoinOrderKey().compareTo(right.getJoinOrderKey());
    }
  }

  @Override
  protected List<SQLWhereFormula> generateOuterJoin(SQLQueryModel query, StringBuilder sql) {
    throw new RuntimeException(Messages.getErrorString("HiveDialect.ERROR_0001_OUTER_JOIN_NOT_SUPPORTED")); //$NON-NLS-1$
  }
  
  @Override
  protected void generateSelect(SQLQueryModel query, StringBuilder sql) {
    sql.append("SELECT "); //$NON-NLS-1$
    if (query.getDistinct()) {
      sql.append("DISTINCT "); //$NON-NLS-1$
    }
    sql.append(Const.CR);
    boolean first = true;
    for (SQLSelection selection : query.getSelections()) {
      if (first) {
        first = false;
        sql.append("          "); //$NON-NLS-1$
      } else {
        sql.append("         ,"); //$NON-NLS-1$
      }
      sql.append(selection.getFormula());
      // Hive does not support column aliases
//      if (selection.getAlias() != null) {
//        sql.append(" AS "); //$NON-NLS-1$
//        sql.append(selection.getAlias());
//      }
      sql.append(Const.CR);
    }
  }

  @Override
  protected void generateFrom(SQLQueryModel query, StringBuilder sql) {
    sql.append("FROM ").append(Const.CR); //$NON-NLS-1$
    if (query.getJoins().isEmpty()) {
      // If no inner joins exist we join with no conditions  
      sql.append(getFromClauseWithTables(query));
    } else {
      // If joins exist get a proper FROM and WHERE clause for the query
      sql.append(getFromAndWhereClauseWithInnerJoins(query));
    }
  }

  @Override
  protected void generateJoins(SQLQueryModel query, StringBuilder sql) {
    // Do nothing.  This has already been taken care of in generateFrom() and generateInnerJoins().
  }

  /**
   * Create a FROM clause by joining the tables of the model without any conditions.
   * 
   * @param query Query Model
   * @return From clause built up by joining all tables together
   */
  private String getFromClauseWithTables(SQLQueryModel query) {
    StringBuilder sql = new StringBuilder();
    Iterator<SQLTable> iter = query.getTables().iterator();
    SQLTable table = iter.next();
    sql.append("          "); //$NON-NLS-1$
    appendTableAndAlias(sql, table);
    while (iter.hasNext()) {
      // Hive does not support more than one table reference.  When more than one table is 
      // used we must explicitly join it.
      sql.append("     JOIN "); //$NON-NLS-1$
      appendTableAndAlias(sql, table);
    }
    sql.append(Const.CR);
    return sql.toString();
  }

  /**
   * Creates a FROM clause by joining tables and their WHERE conditions optimized for Hive.  The basic logic is:
   * 
   * 1.  Honor the user defined Join Order if possible.
   * 2.  Include WHERE condition if only equalities are used.
   * 3.  Joins with WHERE conditions that contain operators other than '=' should be joined without a condition and the 
   *     conditions be placed in the WHERE clause of the query.
   * 
   * @param query Query Model
   * @return String representing FROM and WHERE clause based on the Inner Joins of the query model.
   */
  private String getFromAndWhereClauseWithInnerJoins(SQLQueryModel query) {
    StringBuilder sql = new StringBuilder();
    // Copy of joins so we can manipulate the list
    List<SQLJoin> joins = new ArrayList<SQLJoin>(query.getJoins());
    // Tables already used in join conditions (this is required to ensure tables are not duplicated)
    Set<String> usedTables = new HashSet<String>();
    // SQLJoins with WHERE conditions that must be included in the WHERE clause of the query
    List<SQLJoin> joinsForWhereClause = new LinkedList<SQLJoin>();
    // Honor the sorting order given by Join Order Key 
    Collections.sort(joins, InnerJoinComparator.getInstance());
    SQLJoin join = joins.get(0);
    // Use the LHS of the first join as the anchor table to start the query
    String firstTable = getTableAndAlias(join.getLeftTablename(), join.getLeftTableAlias());
    sql.append("          ").append(firstTable); //$NON-NLS-1$
    sql.append(Const.CR);
    // The first table has now been used in the query
    usedTables.add(firstTable);
    // Connect SQLJoin nodes until we can't connect any more
    connectNode(sql, usedTables, joins, joinsForWhereClause);
    // If there are joins left after we're done connecting nodes they are unreachable
    if (!joins.isEmpty()) {
      throw new RuntimeException(String.format(Messages.getErrorString(
          "HiveDialect.ERROR_0002_JOIN_PATH_NOT_FOUND", //$NON-NLS-1$
          getTableAndAlias(join.getLeftTablename(), join.getLeftTableAlias()),
          getTableAndAlias(join.getRightTablename(), join.getRightTableAlias()))));
    }
    // Add any joins that have where conditions that cannot be put into the ON clause because of Hive's join syntax
    if (!joinsForWhereClause.isEmpty()) {
      generateInnerJoinWhereConditions(query, sql, joinsForWhereClause);
    }
    return sql.toString();
  }

  /**
   * Attempt to connect another {@link SQLJoin} to the query.
   * 
   * @param sql In-progress query string being built
   * @param usedTables Tables already used in this query
   * @param unusedJoins Remaining, unused {@link SQLJoin}s.
   * @param joinsForWhereClause {@link SQLJoin}s with WHERE conditions that have not been used in any ON conditions
   */
  private void connectNode(StringBuilder sql, Set<String> usedTables, List<SQLJoin> unusedJoins,
      List<SQLJoin> joinsForWhereClause) {
    Iterator<SQLJoin> iter = unusedJoins.iterator();
    while (iter.hasNext()) {
      SQLJoin join = iter.next();
      String lhs = getTableAndAlias(join.getLeftTablename(), join.getLeftTableAlias());
      String rhs = getTableAndAlias(join.getRightTablename(), join.getRightTableAlias());
      boolean lhsUsed = usedTables.contains(lhs);
      boolean rhsUsed = usedTables.contains(rhs);
      // Determine if we should reject this join condition, skip it, flip it, or keep it as is
      if (lhsUsed && rhsUsed) {
        // Multiple joins against the same tables.  This is assumed to be not possible.
        throw new RuntimeException(Messages.getErrorString(
            "HiveDialect.ERROR_0003_ADDITIONAL_JOIN_CONDITIONS_FOUND", lhs, rhs)); //$NON-NLS-1$
      } else if (!lhsUsed && !rhsUsed) {
        // If neither of the tables have been used yet skip this join for now.
        continue;
      } else if (!lhsUsed && rhsUsed) {
        // Swap the lhs and rhs so we join unused tables to the chain of used tables
        String t = lhs;
        lhs = rhs;
        rhs = t;
      } else {
        // Keep original order, used on left, unused on right.
      }
      // We've found a join to be included, remove it from the list of unused joins
      iter.remove();
      // Join the RHS table
      usedTables.add(rhs);
      sql.append("          JOIN "); //$NON-NLS-1$
      sql.append(rhs);
      // Check for a valid join formula
      if (!isValidJoinFormula(join.getSqlWhereFormula().getFormula())) {
        // SQLJoins with invalid Hive ON clause join formulas will be added in the WHERE clause
        joinsForWhereClause.add(join);
      } else {
        // Use the Hive-valid join condition in the ON clause of this join
        sql.append(" ON ( ").append(join.getSqlWhereFormula().getFormula()).append(" )"); //$NON-NLS-1$ //$NON-NLS-2$
      }
      sql.append(Const.CR);
      // We successfully found a new SQLJoin node to attach to the query, attempt to connect another
      connectNode(sql, usedTables, unusedJoins, joinsForWhereClause);
      // And stop looking here, call to connectNode(..) above will start from the top
      break;
    }
  }

  /**
   * Checks if a formula is a valid Hive join condition.
   * 
   * @param formula SQL where (join) formula
   * @return True if the formula can be used in the ON condition of a join in Hive.
   */
  protected boolean isValidJoinFormula(String formula) {
    return !INVALID_JOIN_OPERATORS.matcher(formula).find();
  }

  /**
   * Add join conditions that contain operators other than equalities to the WHERE condition.
   * 
   * @param query Query Model
   * @param sql In-progress query string being built
   * @param joins {@link SQLJoin}s with WHERE conditions that have not been used in any ON clauses
   */
  private void generateInnerJoinWhereConditions(SQLQueryModel query, StringBuilder sql, List<SQLJoin> joins) {
    if (!joins.isEmpty()) {
      boolean first = true;
      sql.append("WHERE").append(Const.CR); //$NON-NLS-1$
      for (SQLJoin join : joins) {
        if (first) {
          sql.append("          ( "); //$NON-NLS-1$
          first = false;
        } else {
          sql.append("      AND ( "); //$NON-NLS-1$
        }
        sql.append(join.getSqlWhereFormula().getFormula());
        sql.append(" )").append(Const.CR); //$NON-NLS-1$
      }
    }
  }

  /**
   * Append a table's name and alias to the end of {@code sql}.
   * 
   * @see #getTableAndAlias(String, String)
   */
  private void appendTableAndAlias(StringBuilder sql, SQLTable table) {
    sql.append(getTableAndAlias(table.getTableName(), table.getAlias()));
  }

  /**
   * Get the concatenation of table name and alias (if it exists).
   * 
   * @param table Name of table
   * @param alias Alias for table
   * @return "{@code table}" or "{@code table} {@code alias}" if alias exists.
   */
  private String getTableAndAlias(String table, String alias) {
    String tableAndAlias = table;
    if (!Const.isEmpty(alias)) {
      tableAndAlias += " " + alias; //$NON-NLS-1$
    }
    return tableAndAlias;
  }
}