package mondrian.olap;

import mondrian.rolap.BatchTestCase;
import mondrian.spi.Dialect;
import mondrian.test.SqlPattern;

public class IdBatchResolverTest  extends BatchTestCase {

    public void testBroken() {

        propSaver.set(propSaver.properties.IgnoreInvalidMembersDuringQuery, true);
        executeQuery("select from sales");
        assertExprReturns(
            "StrToMember(\"[Marital Status].[Separated]\").Hierarchy.Name",
            "Marital Status");
    }


    public void testSimpleEnumeratedSet() {
        propSaver.set(propSaver.properties.GenerateFormattedSql, true);

        assertQueryReturns(
            "select "
                + "{[Product].[Food].[Dairy],"
                + "[Product].[Food].[Deli],"
                + "[Product].[Food].[Eggs],"
                + "[Product].[Food].[Produce],"
                + "[Product].[Food].[Starchy Foods]}"
                + "on 0 from sales", "");

        assertQuerySql(
            "select "
            + "{[Product].[Food].[Dairy],"
            + "[Product].[Food].[Deli],"
            + "[Product].[Food].[Eggs],"
            + "[Product].[Food].[Produce],"
            + "[Product].[Food].[Starchy Foods]}"
            + "on 0 from sales",
            new SqlPattern[] {
                new SqlPattern(
                    Dialect.DatabaseProduct.MYSQL,
                    "select\n"
                    + "    `product_class`.`product_department` as `c0`\n"
                    + "from\n"
                    + "    `product` as `product`,\n"
                    + "    `product_class` as `product_class`\n"
                    + "where\n"
                    + "    `product`.`product_class_id` = `product_class`.`product_class_id`\n"
                    + "and\n"
                    + "    (`product_class`.`product_family` = 'Food')\n"
                    + "and\n"
                    + "    ( UPPER(`product_class`.`product_department`) IN (UPPER('Dairy'),UPPER('Deli'),UPPER('Eggs'),UPPER('Produce'),UPPER('Starchy Foods')))\n"
                    + "group by\n"
                    + "    `product_class`.`product_department`\n"
                    + "order by\n"
                    + "    ISNULL(`product_class`.`product_department`) ASC, `product_class`.`product_department` ASC", null)
            });
    }


    public void testAnalyzerMdx() {
        propSaver.set(propSaver.properties.GenerateFormattedSql, true);

        assertQueryReturns(
            "WITH\n"
                + "SET [*NATIVE_CJ_SET] AS 'NONEMPTYCROSSJOIN([*BASE_MEMBERS__Promotions_],[*BASE_MEMBERS__Store_])'\n"
                + "SET [*BASE_MEMBERS__Store_] AS '{[Store].[USA].[WA].[Bellingham],[Store].[USA].[CA].[Beverly Hills],[Store].[USA].[WA].[Bremerton],[Store].[USA].[CA].[Los Angeles]}'\n"
                + "SET [*SORTED_COL_AXIS] AS 'ORDER([*CJ_COL_AXIS],[Promotions].CURRENTMEMBER.ORDERKEY,BASC)'\n"
                + "SET [*BASE_MEMBERS__Measures_] AS '{[Measures].[*FORMATTED_MEASURE_0]}'\n"
                + "SET [*CJ_ROW_AXIS] AS 'GENERATE([*NATIVE_CJ_SET], {([Store].CURRENTMEMBER)})'\n"
                + "SET [*BASE_MEMBERS__Promotions_] AS '{[Promotions].[Bag Stuffers],[Promotions].[Best Savings],[Promotions].[Big Promo],[Promotions].[Big Time Discounts],[Promotions].[Big Time Savings],[Promotions].[Bye Bye Baby]}'\n"
                + "SET [*SORTED_ROW_AXIS] AS 'ORDER([*CJ_ROW_AXIS],[Store].CURRENTMEMBER.ORDERKEY,BASC,ANCESTOR([Store].CURRENTMEMBER,[Store].[Store State]).ORDERKEY,BASC)'\n"
                + "SET [*CJ_COL_AXIS] AS 'GENERATE([*NATIVE_CJ_SET], {([Promotions].CURRENTMEMBER)})'\n"
                + "MEMBER [Measures].[*FORMATTED_MEASURE_0] AS '[Measures].[Unit Sales]', FORMAT_STRING = 'Standard', SOLVE_ORDER=500\n"
                + "SELECT\n"
                + "CROSSJOIN([*SORTED_COL_AXIS],[*BASE_MEMBERS__Measures_]) ON COLUMNS\n"
                + ",NON EMPTY\n"
                + "[*SORTED_ROW_AXIS] ON ROWS\n"
                + "FROM [Sales]",""
        );

        assertQuerySql(
            "WITH\n"
            + "SET [*NATIVE_CJ_SET] AS 'NONEMPTYCROSSJOIN([*BASE_MEMBERS__Promotions_],[*BASE_MEMBERS__Store_])'\n"
            + "SET [*BASE_MEMBERS__Store_] AS '{[Store].[USA].[WA].[Bellingham],[Store].[USA].[CA].[Beverly Hills],[Store].[USA].[WA].[Bremerton],[Store].[USA].[CA].[Los Angeles]}'\n"
            + "SET [*SORTED_COL_AXIS] AS 'ORDER([*CJ_COL_AXIS],[Promotions].CURRENTMEMBER.ORDERKEY,BASC)'\n"
            + "SET [*BASE_MEMBERS__Measures_] AS '{[Measures].[*FORMATTED_MEASURE_0]}'\n"
            + "SET [*CJ_ROW_AXIS] AS 'GENERATE([*NATIVE_CJ_SET], {([Store].CURRENTMEMBER)})'\n"
            + "SET [*BASE_MEMBERS__Promotions_] AS '{[Promotions].[Bag Stuffers],[Promotions].[Best Savings],[Promotions].[Big Promo],[Promotions].[Big Time Discounts],[Promotions].[Big Time Savings],[Promotions].[Bye Bye Baby]}'\n"
            + "SET [*SORTED_ROW_AXIS] AS 'ORDER([*CJ_ROW_AXIS],[Store].CURRENTMEMBER.ORDERKEY,BASC,ANCESTOR([Store].CURRENTMEMBER,[Store].[Store State]).ORDERKEY,BASC)'\n"
            + "SET [*CJ_COL_AXIS] AS 'GENERATE([*NATIVE_CJ_SET], {([Promotions].CURRENTMEMBER)})'\n"
            + "MEMBER [Measures].[*FORMATTED_MEASURE_0] AS '[Measures].[Unit Sales]', FORMAT_STRING = 'Standard', SOLVE_ORDER=500\n"
            + "SELECT\n"
            + "CROSSJOIN([*SORTED_COL_AXIS],[*BASE_MEMBERS__Measures_]) ON COLUMNS\n"
            + ",NON EMPTY\n"
            + "[*SORTED_ROW_AXIS] ON ROWS\n"
            + "FROM [Sales]",
            new SqlPattern[]{
                new SqlPattern(
                    Dialect.DatabaseProduct.MYSQL,
                    "select\n"
                    + "    `promotion`.`promotion_name` as `c0`\n"
                    + "from\n"
                    + "    `promotion` as `promotion`\n"
                    + "where\n"
                    + "    ( UPPER(`promotion`.`promotion_name`) IN (UPPER('Bag Stuffers'),UPPER('Best Savings'),UPPER('Big Promo'),UPPER('Big Time Discounts'),UPPER('Big Time Savings'),UPPER('Bye Bye Baby')))\n"
                    + "group by\n"
                    + "    `promotion`.`promotion_name`\n"
                    + "order by\n"
                    + "    ISNULL(`promotion`.`promotion_name`) ASC, `promotion`.`promotion_name` ASC", null
                )
            });
    }

    public void _testParentChild() {
        // P-C can't benefit from the same batch strategy.  Resolution
        // can still be successful, but we shouldn't see an attempt at
        // a SQL query constrained by a list of child names.

        //TODO verify no member name IN query.

        assertQueryReturns(
            "WITH\n"
            + "SET [*NATIVE_CJ_SET] AS 'FILTER([*BASE_MEMBERS__Employees_], NOT ISEMPTY ([Measures].[Count]))'\n"
            + "SET [*BASE_MEMBERS__Employees_] AS '{[Employees].[Sheri Nowmer].[Derrick Whelply].[Laurie Borges].[Ed Young].[Gregory Whiting].[Melissa Marple],[Employees].[Sheri Nowmer].[Derrick Whelply].[Laurie Borges].[Ed Young].[Gregory Whiting].[Merrill Steel]}'\n"
            + "SET [*BASE_MEMBERS__Measures_] AS '{[Measures].[*FORMATTED_MEASURE_0]}'\n"
            + "SET [*CJ_ROW_AXIS] AS 'GENERATE([*NATIVE_CJ_SET], {([Employees].CURRENTMEMBER)})'\n"
            + "SET [*SORTED_ROW_AXIS] AS 'ORDER([*CJ_ROW_AXIS],[Employees].CURRENTMEMBER.ORDERKEY,DESC)'\n"
            + "MEMBER [Measures].[*FORMATTED_MEASURE_0] AS '[Measures].[Count]', FORMAT_STRING = '#,#', SOLVE_ORDER=500\n"
            + "SELECT\n"
            + "[*BASE_MEMBERS__Measures_] ON COLUMNS\n"
            + ",[*SORTED_ROW_AXIS] ON ROWS\n"
            + "FROM [HR]", "");
    }

}