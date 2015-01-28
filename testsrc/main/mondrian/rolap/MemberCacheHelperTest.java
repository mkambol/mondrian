/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (c) 2002-2014 Pentaho Corporation
// All Rights Reserved.
*/
package mondrian.rolap;

import mondrian.spi.Dialect;
import mondrian.test.SqlPattern;

public class MemberCacheHelperTest extends BatchTestCase {

    public void testIndividualMembersAreNotQueriedForWhenInLevelCache() {
        propSaver.properties.GenerateFormattedSql.set(true);

        // load the level members cache (w/ DefaultTupleConstraint)
        executeQuery(
            "select [Education Level].[Education Level].members on 0 from sales");

        String mysql = "select\n"
            + "    `customer`.`education` as `c0`\n"
            + "from\n"
            + "    `customer` as `customer`\n"
            + "where\n"
            + "    UPPER(`customer`.`education`) = UPPER('";

        assertNoQuerySql("select {[Education Level].[Bachelors Degree]," +
                "[Education Level].[Graduate Degree]," +
                "[Education Level].[High School Degree]," +
                "[Education Level].[Partial College]} on 0 from sales",
            new SqlPattern[]{
                new SqlPattern(
                    Dialect.DatabaseProduct.MYSQL,
                    mysql, null)});
    }

}