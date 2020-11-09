package it.unibo.conversational.database;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import it.unibo.conversational.Utils;
import it.unibo.conversational.datatypes.Entity;
import it.unibo.conversational.datatypes.Mapping;
import it.unibo.conversational.olap.Operator;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static it.unibo.conversational.database.DBmanager.*;

/**
 * Interacting with the database SQL query.
 */
public final class QueryGenerator {
    private static final Logger L = LoggerFactory.getLogger(QueryGenerator.class);
    // This meta-structures are used by the infer and typeChecking steps
    /**
     * Aggregation operator for each measure
     */
    private static Map<Cube, Map<String, Set<Entity>>> operatorOfMeasure = Maps.newLinkedHashMap();
    /**
     * Members of each each level (note that a member can be in more levels)
     */
    private static Map<Cube, Map<String, Set<Entity>>> membersofLevels = Maps.newLinkedHashMap();
    /**
     * Levels of each member
     */
    private static Map<Cube, Map<String, Set<Entity>>> levelsOfMembers = Maps.newLinkedHashMap();
    /**
     * From string to level
     */
    private static Map<Cube, Map<String, Entity>> string2level = Maps.newLinkedHashMap();
    /**
     * Year levels
     */
    private static Map<Cube, Set<Entity>> yearLevels = Maps.newLinkedHashMap();
    /**
     * Synonyms
     */
    private static Map<Cube, Map<List<String>, List<Entity>>> syns = Maps.newLinkedHashMap();

    /**
     * @param cube cube
     * @return Aggregation operator for each measure of the given cube
     */
    public static Map<String, Set<Entity>> operatorOfMeasure(final Cube cube) {
        return operatorOfMeasure.get(cube);
    }

    /**
     * @param cube cube
     * @return Members of each each level (note that a member can be in more levels) of the given cube
     */
    public static Map<String, Set<Entity>> membersofLevels(final Cube cube) {
        return membersofLevels.get(cube);
    }

    /**
     * @param cube cube
     * @return Levels of each member of the given cube
     */
    public static Map<String, Set<Entity>> levelsOfMembers(final Cube cube) {
        return levelsOfMembers.get(cube);
    }

    /**
     * @param cube cube
     * @return From string to level of the given cube
     */
    public static Map<String, Entity> string2level(final Cube cube) {
        return string2level.get(cube);
    }

    /**
     * @param cube cube
     * @return Year levels of the given cube
     */
    public static Set<Entity> yearLevels(final Cube cube) {
        return yearLevels.get(cube);
    }

    /**
     * @param cube cube
     * @return Synonyms for the given cube
     */
    public static Map<List<String>, List<Entity>> syns(final Cube cube) {
        return syns.compute(cube, (k, v) -> v == null ? Maps.newLinkedHashMap() : v);
    }

    // Initialize the metra structure at the beginning, this could be done by need
    static {
        for (final Cube cube : Config.getCubes()) {
            L.debug("Loading meta-data from: " + cube.getFactTable());
            operatorOfMeasure.put(cube, QueryGenerator.getOperatorOfMeasure(cube));
            populateMembers(cube);
            yearLevels.put(cube, QueryGenerator.getYearLevels(cube));
        }
        L.debug("Done loading.");
    }

    private QueryGenerator() {
    }

    /**
     * @return get the fact
     */
    public static Pair<String, String> getFactTable(final Cube cube) {
        final List<Pair<String, String>> acc = Lists.newLinkedList();
        executeMetaQuery(cube, "SELECT " + id(tabTABLE) + ", " + name(tabTABLE) + " FROM `" + tabTABLE + "` WHERE `" + type(tabTABLE) + "` = \"" + TableTypes.FT + "\"", res -> {
            res.next();
            acc.add(Pair.of(res.getString(id(tabTABLE)), res.getString(name(tabTABLE))));
        });
        return acc.remove(0);
    }

    public static Pair<String, String> getTabDetails(final Cube cube, String idFT, String idTable) {
        final List<Pair<String, String>> acc = Lists.newLinkedList();
        executeMetaQuery(cube, "SELECT " + name(tabTABLE) + " FROM `" + tabTABLE + "` WHERE `" + id(tabTABLE) + "` = \"" + idTable + "\"", resDet -> {
            executeMetaQuery(cube, "SELECT " + name(tabCOLUMN) + " FROM `" + tabCOLUMN + "` C INNER JOIN `" + tabRELATIONSHIP + "` R ON C." + id(tabRELATIONSHIP) + " = R." + id(tabRELATIONSHIP) + " WHERE `" + colRELTAB1 + "` = \"" + idFT + "\" AND `" + colRELTAB2 + "` = \"" + idTable + "\"", resCol -> {
                resDet.next();
                resCol.next();
                acc.add(Pair.of(resDet.getString(name(tabTABLE)), resCol.getString(name(tabCOLUMN))));
            });
        });
        return acc.remove(0);
    }

    public static String getTable(final Cube cube, final String... attributes) {
        final List<String> acc = Lists.newLinkedList();
        executeMetaQuery(cube, "select distinct table_name " +
                "from `" + tabLEVEL + "` l join `" + tabCOLUMN + "` c on l.level_name = c.column_name join `" + tabTABLE + "` t on c.table_id = t.table_id " +
                "where level_name in (" + Arrays.stream(attributes).map(a -> cube.getDbms().equals("mysql")? a : a.toUpperCase()).reduce((a, b) -> "'" + a + "','" + b + "'").get() + ")", res -> {
            while (res.next()) {
                final String table = res.getString(name(tabTABLE));
                if (!table.equals(cube.getFactTable())) {
                    acc.add(table);
                }
            }
        });
        return acc.remove(0);
    }

    /**
     * Functional dependency
     *
     * @param specific specific attribute
     * @param generic  generic attribute
     * @return Map of <generic value, specific values> entries
     */
    public static Map<String, List<String>> getFunctionalDependency2(final Cube cube, final String specific, final String generic) {
        final Map<String, List<String>> tables = Maps.newLinkedHashMap();
        executeDataQuery(cube, "select distinct " + specific + ", " + generic + " from " + getTable(cube, specific, generic), res -> {
            while (res.next()) {
                if (!tables.containsKey(res.getString(generic))) {
                    tables.put(get(res, 2), Lists.newArrayList(get(res, 1)));
                } else {
                    tables.get(get(res, 2)).add(get(res, 1));
                }
            }
        });
        return tables;
    }

    /**
     * Functional dependency
     *
     * @param specific specific attribute
     * @param generic  generic attribute
     * @return Map of <specific value, generic value> entries
     */
    public static Map<String, String> getFunctionalDependency(final Cube cube, final String specific, final String generic) {
        final Map<String, String> tables = Maps.newLinkedHashMap();
        executeDataQuery(cube, "select distinct " + specific + ", " + generic + " from " + getTable(cube, specific, generic), res -> {
            while (res.next()) {
                if (tables.containsKey(res.getString(specific))) {
                    throw new IllegalArgumentException("Overriding " + res.getString(specific));
                }
                tables.put(get(res, 1), get(res, 2));
            }
        });
        return tables;
    }

    private static String get(ResultSet res, int idx) throws SQLException {
        switch (res.getMetaData().getColumnClassName(idx)) {
            case "java.sql.Timestamp":
                final Date date = new Date();
                date.setTime(res.getTimestamp(idx).getTime());
                return new SimpleDateFormat("yyyy-MM-dd").format(date);
            default:
                return res.getString(idx);
        }
    }

    /**
     * @param level level
     * @return the entity corresponding to the level
     */
    public static Entity getLevel(final Cube cube, String level) {
        return getLevel(cube, level, true);
    }

    /**
     * @param level          level
     * @param throwException whether to throw exception if the level is missing
     * @return the entity corresponding to the level
     */
    public static Entity getLevel(final Cube cube, final String level, final boolean throwException) {
        if (throwException && !string2level(cube).containsKey(level.toLowerCase())) {
            throw new IllegalArgumentException("Cannot find level " + level);
        }
        return string2level(cube).get(level.toLowerCase());
    }

    public static Map<String, Entity> getLevels(final Cube cube) {
        return string2level.get(cube);
    }

    /**
     * Describe a level
     *
     * @param level level name
     * @param topN  number of entries to retrieve
     * @return JSON object
     */
    public static JSONObject describeLevel2JSON(final Cube cube, final String level, final int topN) {
        final JSONObject o = new JSONObject();
        describeLevel(cube, level, topN).forEach(p -> o.put(p.getLeft(), p.getRight()));
        return o;
    }

    /**
     * Describe a level
     *
     * @param level level name
     * @param topN  number of entries to retrieve
     * @return Set of pairs (e.g., {(min, 1), (max, 10), ...})
     */
    public static Set<Pair<String, Object>> describeLevel(final Cube cube, final String level, final int topN) {
        final Set<Pair<String, Object>> attributes = Sets.newLinkedHashSet();
        final Set<String> members = Sets.newLinkedHashSet();
        DBmanager.executeMetaQuery(cube,
                "select level_type, level_description, cardinality, min, max, avg, isDescriptive, mindate, maxdate, member_name "
                        + "from `" + tabLEVEL + "` l left join `" + tabMEMBER + "` m on (l.level_id = m.level_id) "
                        + "where level_name = \"" + (cube.getDbms().equals("mysql") ? level : level.toUpperCase()) + "\" " + (cube.getDbms().equals("oracle")? " and ROWNUM <= " + topN + " " : "")
                        + (cube.getDbms().equals("mysql")? "limit " + topN : ""),
                res -> {
                    final BiFunction<String, Set<Pair<String, Object>>, Object> get = (t, u) -> {
                        try {
                            if (res.getObject(t) != null) {
                                attributes.add(Pair.of(t, res.getObject(t)));
                            }
                        } catch (final SQLException e) {
                            e.printStackTrace();
                        }
                        return null;
                    };
                    while (res.next()) {
                        get.apply("min", attributes);
                        get.apply("max", attributes);
                        get.apply("mindate", attributes);
                        get.apply("maxdate", attributes);
                        get.apply("avg", attributes);
                        get.apply("isDescriptive", attributes);
                        get.apply("level_description", attributes);
                        if (res.getString("member_name") != null) {
                            members.add(res.getString("member_name"));
                        }
                    }
                });
        if (!members.isEmpty()) {
            attributes.add(Pair.of("members", members));
        }
        return attributes;
    }

    /**
     * @param id id of the language predicate
     * @return the <type, name> of the language predicate
     */
    public static Pair<String, String> getOperator(final Cube cube, final String id) {
        final List<Pair<String, String>> acc = Lists.newLinkedList();
        DBmanager.executeMetaQuery(cube,
                "SELECT " + type(tabLANGUAGEOPERATOR) + ", " + name(tabLANGUAGEOPERATOR) + " FROM `" + tabLANGUAGEOPERATOR + "` WHERE " + id(tabLANGUAGEOPERATOR) + " = " + id,
                res -> {
                    while (res.next()) {
                        acc.add(Pair.of(res.getString(type(tabLANGUAGEOPERATOR)), res.getString(name(tabLANGUAGEOPERATOR))));
                    }
                }
        );
        return acc.remove(0);
    }

    /**
     * @param id id of the language predicate
     * @return the <type, name> of the language predicate
     */
    public static Pair<String, String> getPredicate(final Cube cube, final String id) {
        final List<Pair<String, String>> acc = Lists.newLinkedList();
        DBmanager.executeMetaQuery(cube,
                "SELECT " + type(tabLANGUAGEPREDICATE) + ", " + name(tabLANGUAGEPREDICATE) + " FROM `" + tabLANGUAGEPREDICATE + "` WHERE " + id(tabLANGUAGEPREDICATE) + " = " + id,
                res -> {
                    while (res.next()) {
                        acc.add(Pair.of(res.getString(type(tabLANGUAGEPREDICATE)), res.getString(name(tabLANGUAGEPREDICATE))));
                    }
                }
        );
        return acc.remove(0);
    }

    /**
     * @return a map <measure, operators> (i.e., the operators that are appliable to the given measure)
     */
    public static Map<String, Set<Entity>> getOperatorOfMeasure(final Cube cube) {
        final Map<String, Set<Entity>> map = Maps.newLinkedHashMap();
        final String query =
                "select gm." + id(tabGROUPBYOPERATOR) + ", gm." + id(tabMEASURE) + ", " + name(tabMEASURE) + ", " + name(tabGROUPBYOPERATOR) + " "
                + "from `" + tabGRBYOPMEASURE + "` gm, `" + tabMEASURE + "` m, `" + tabGROUPBYOPERATOR + "` g "
                + "where g." + id(tabGROUPBYOPERATOR) + " = gm." + id(tabGROUPBYOPERATOR) + " and gm." + id(tabMEASURE) + " = m." + id(tabMEASURE);
        DBmanager.executeMetaQuery(cube,
                query,
                res -> {
                    while (res.next()) { // for each group by operator
                        final String mea = res.getString(name(tabMEASURE));
                        final String id = res.getString(id(tabGROUPBYOPERATOR));
                        final String op = res.getString(name(tabGROUPBYOPERATOR));
                        final Set<Entity> val = map.getOrDefault(mea, Sets.newLinkedHashSet());
                        val.add(new Entity(id, op, tabGROUPBYOPERATOR));
                        map.put(mea, val);
                    }
                });
        return map;
    }

    private static void populateMembers(final Cube cube) {
        final Map<String, Set<Entity>> attributes = Maps.newLinkedHashMap();
        final Map<String, Set<Entity>> members = Maps.newLinkedHashMap();
        final Map<String, Entity> attrEntity = Maps.newLinkedHashMap();
        DBmanager.executeMetaQuery(cube,
                "select m." + id(tabMEMBER) + ", m." + name(tabMEMBER)
                                + ", l." + id(tabLEVEL) + ", l." + name(tabLEVEL) + ", l." + type(tabLEVEL)
                                + ", t." + id(tabTABLE)  + ", t." + name(tabTABLE)
                                + ", c." + name(tabCOLUMN) + " "
                        + "from `" + tabLEVEL +"` l JOIN `" + tabCOLUMN + "` c ON(l." + id(tabCOLUMN) + " = c." + id(tabCOLUMN) + ") JOIN `"
                            + tabTABLE + "` t ON(c." + id(tabTABLE) + " = t." + id(tabTABLE) + ") LEFT JOIN `"
                            + tabMEMBER + "` m on (l." + id(tabLEVEL) + " = m." + id(tabLEVEL) + ")",
                res -> {
                    while (res.next()) {
                        final Set<Entity> tmpMembers = members.getOrDefault(res.getString(name(tabLEVEL)), Sets.newLinkedHashSet());
                        final Set<Entity> tmpAttributes = attributes.getOrDefault(res.getString(name(tabMEMBER)), Sets.newLinkedHashSet());
                        if (res.getString(id(tabMEMBER)) != null) {
                            tmpMembers.add(new Entity(
                                    res.getString(id(tabMEMBER)),
                                    res.getString(name(tabMEMBER)),
                                    res.getString(id(tabLEVEL)),
                                    res.getString(name(tabCOLUMN)),
                                    Utils.getDataType(res.getString(type(tabLEVEL))),
                                    tabMEMBER,
                                    res.getString(name(tabTABLE))));
                            members.put(res.getString(name(tabLEVEL)), tmpMembers);
                        }

                        final Entity level = new Entity(
                                res.getString(id(tabLEVEL)),
                                res.getString(name(tabLEVEL)),
                                res.getString(id(tabTABLE)),
                                res.getString(name(tabCOLUMN)),
                                Utils.getDataType(res.getString(type(tabLEVEL))),
                                tabLEVEL,
                                res.getString(name(tabTABLE)));
                        if (res.getString(id(tabMEMBER)) != null) {
                            tmpAttributes.add(level);
                            attributes.put(res.getString(name(tabMEMBER)), tmpAttributes);
                        }
                        attrEntity.put(res.getString(name(tabLEVEL)).toLowerCase(), level);
                    }
                });
        membersofLevels.put(cube, members);
        levelsOfMembers.put(cube, attributes);
        string2level.put(cube, attrEntity);
    }

    /**
     * Returns the member of each level.
     *
     * @return set members (i.e., entities) for each levels
     */
    public static Map<String, Set<Entity>> getMembersOfLevels(final Cube cube) {
        return membersofLevels.get(cube);
    }

    /**
     * Returns the member of each level.
     *
     * @return set members (i.e., entities) for each levels
     */
    public static Map<String, Set<Entity>> getLevelsOfMembers(final Cube cube) {
        return levelsOfMembers.get(cube);
    }

    /**
     * Get levels with type "year". TODO: in this version only the level 'the_year' is retrieved. This is because it not easy to find "year" levels.
     *
     * @return levels with type "year"
     */
    public static Set<Entity> getYearLevels(final Cube cube) {
        return string2level(cube).entrySet().stream().filter(e -> e.getKey().toLowerCase().equals("year") || e.getKey().toLowerCase().equals("the_year")).map(Map.Entry::getValue).collect(Collectors.toSet());
    }

    /**
     * Save a query in the dataset.
     *
     * @param query     nl query
     * @param gbset     correct group by set
     * @param predicate correct selection clause
     * @param selclause correct measure clause
     */
    public static void saveQuery(final Cube cube, final String query, final String gbset, final String predicate, final String selclause) {
        DBmanager.insertMeta(cube,
                "INSERT INTO `" + tabQUERY + "` (`" + colQueryText + "`, `" + colQueryGBset + "`, `" + colQueryMeasClause + "`, `" + colQuerySelClause + "`) " + "VALUES (\"" + query + "\", \"" + gbset + "\", \"" + selclause + "\", \"" + predicate + "\")",
                PreparedStatement::execute);
    }

    private static String checkNull(final Object toCheck, final String res) {
        return (toCheck == null ? "" : res);
    }

    /**
     * Save a OLAP session in the dataset.
     *
     * @param sessionid    session id
     * @param annotationid annotation id
     * @param valueEn      value in English
     * @param valueIta     value in Italian
     * @param limit        limit
     * @param fullquery    full query to serialize
     * @param operator     OLAP operator to serialize
     */
    public static void saveSession(final Cube cube, final String sessionid, final String annotationid, final String valueEn, final String valueIta, final String limit, final Mapping fullquery, final Operator operator) {
        final String sql =
                "INSERT INTO `" + tabOLAPsession + "` (timestamp, session_id, value_en"
                        + checkNull(annotationid, ", annotation_id")
                        + checkNull(valueIta, ", value_ita")
                        + checkNull(limit, ", limit")
                        + checkNull(fullquery, ", fullquery_serialized")
                        + checkNull(fullquery, ", fullquery_tree")
                        + checkNull(operator, ", olapoperator_serialized")
                        + ")"
                        + "VALUES (" + //
                        +System.currentTimeMillis() + "," //
                        + "\"" + (sessionid == null ? "" : sessionid) + "\","//
                        + "\"" + (valueEn == null ? "" : valueEn) + "\""//
                        + checkNull(annotationid, ", \"" + annotationid + "\"")
                        + checkNull(valueIta, ", \"" + valueIta + "\"")
                        + checkNull(limit, ", \"" + limit + "\"")
                        + (fullquery != null ? ", ?" : "")
                        + (fullquery != null ? ", ?" : "")
                        + (operator != null ? ", ?" : "")
                        + ")";
        DBmanager.insertMeta(cube, sql, pstmt -> {
            if (fullquery != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ObjectOutputStream oout = new ObjectOutputStream(baos);
                oout.writeObject(fullquery);
                pstmt.setBytes(1, baos.toByteArray());
                oout.close();
                pstmt.setString(2, fullquery.toStringTree());
            }
            if (operator != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ObjectOutputStream oout = new ObjectOutputStream(baos);
                oout.writeObject(operator);
                pstmt.setBytes(3, baos.toByteArray());
                oout.close();
            }
            pstmt.execute();
        });
    }

    /**
     * @param sessionid session to be dropped
     */
    public static void dropSession(final Cube cube, final String sessionid) {
        DBmanager.insertMeta(cube, "DELETE FROM OLAPsession WHERE session_id = \"" + sessionid + "\"", PreparedStatement::execute);
    }

    /**
     * @param sessionid session to check
     * @param fullquery ground truth on full query
     * @param session   ground truth on session result
     * @return map of statistics
     * @throws IOException in case or error
     */
    public static Map<String, Object> getSessionStatistics(final Cube cube, final String sessionid, final Mapping fullquery, final Mapping session) throws IOException {
        final String sql =
                cube.getDbms().equalsIgnoreCase("mysql") ?
                "select value_en, timestamp, fullquery_serialized, olapoperator_serialized "
                        + "from OLAPsession where value_en in (\"read\", \"navigate\", \"reset\") and session_id = \"" + sessionid + "\" "
                        + "and timestamp >= (select timestamp from OLAPsession where session_id = \"" + sessionid + "\" and value_en = \"read\" order by 1 desc limit 1)" :
                "with timest as (select `TIMESTAMP` as timest from OLAPsession where session_id = \"" + sessionid + "\" and value_en = \"read\" and rownum <= 1 order by 1 desc) "
                    + "select value_en, `TIMESTAMP`, fullquery_serialized, olapoperator_serialized "
                    + "from OLAPsession, timest "
                    + "where value_en in (\"read\", \"navigate\", \"reset\") and session_id = \"" + sessionid + "\" and `TIMESTAMP` >= timest.timest";
        final Map<String, Long> lookupTime = Maps.newLinkedHashMap();
        final Map<String, Mapping> lookupQuery = Maps.newLinkedHashMap();
        final Map<String, Operator> lookupOperator = Maps.newLinkedHashMap();
        DBmanager.executeMetaQuery(cube, sql, res -> {
            while (res.next()) {
                lookupTime.put(res.getString(1), res.getLong(2));
                if (res.getBytes(3) != null) {
                    final ObjectInputStream objectIn = new ObjectInputStream(new ByteArrayInputStream(res.getBytes(3)));
                    lookupQuery.put(res.getString(1), (Mapping) objectIn.readObject());
                }
                if (res.getBytes(4) != null) {
                    final ObjectInputStream objectIn = new ObjectInputStream(new ByteArrayInputStream(res.getBytes(4)));
                    lookupOperator.put(res.getString(1), (Operator) objectIn.readObject());
                }
            }
        });
        if (lookupOperator.isEmpty()) {
            throw new IllegalArgumentException("Could not find session: " + sessionid);
        }
        final Map<String, Object> statistics = Maps.newLinkedHashMap();
        statistics.put("fullquery_time", (lookupTime.get("navigate") - lookupTime.get("read")) / 1000);
        statistics.put("session_time", (lookupTime.get("reset") - lookupTime.get("read")) / 1000);
        statistics.put("operator_time", ((long) statistics.get("session_time") - (long) statistics.get("fullquery_time")) / 2);
        statistics.put("fullquery_sim", lookupQuery.get("navigate").similarity(fullquery));
        statistics.put("session_sim", lookupQuery.get("reset").similarity(session));
        statistics.put("fullquery", lookupQuery.get("navigate").toStringTree());
        statistics.put("session", lookupQuery.get("reset").toStringTree());
        statistics.put("fullquery_gt", fullquery.toStringTree());
        statistics.put("session_gt", session.toStringTree());
        statistics.putAll(getSessionCounts(cube, sessionid));
        L.debug("GTR: " + fullquery.toStringTree());
        L.debug("RET: " + lookupQuery.get("navigate").toStringTree());
        L.debug("GTR: " + session.toStringTree());
        L.debug("RET: " + lookupQuery.get("reset").toStringTree());
        return statistics;
    }

    /**
     * @param sessionid session to check
     * @return number of steps in section
     */
    public static Map<String, Double> getSessionCounts(final Cube cube, final String sessionid) {
        final Map<String, Double> statistics = Maps.newLinkedHashMap();

        String sql =
                cube.getDbms().equals("oracle") ?
                        "with hightimest as (select timestamp as t1 from OLAPsession where session_id = '" + sessionid + "' and value_en = 'reset' and rownum <=1 order by 1 desc), "
                                + "lowtimest  as (select timestamp as t2 from OLAPsession where session_id = '" + sessionid + "' and value_en = 'read' and rownum <=1 order by 1 desc) "
                                + "select count(*) as steps, count(distinct case when annotation_id is null or annotation_id = '' then null else annotation_id end) as annotations "
                                + "from OLAPsession, hightimest, lowtimest "
                                + "where value_en <> 'navigate' and session_id = '" + sessionid + "' and timestamp < hightimest.t1 and timestamp > lowtimest.t2"
                        :
                        "select count(*) as steps, count(distinct case when annotation_id is null or annotation_id = '' then null else annotation_id end) as annotations "
                                + "from OLAPsession "
                                + "where `value_en` <> \"navigate\" and session_id = \"" + sessionid + "\" and "
                                + "    `timestamp` < (select `timestamp` from OLAPsession where session_id = \"" + sessionid + "\" and value_en = \"reset\" order by 1 desc limit 1) and "
                                + "    `timestamp` > (select `timestamp` from olapsession where session_id = \"" + sessionid + "\" and value_en = \"read\"  order by 1 desc limit 1)";
        DBmanager.executeMetaQuery(cube, sql, res -> {
            while (res.next()) {
                statistics.put("session_iterations", res.getInt(1) * 1.0);
                statistics.put("session_ambiguities", res.getInt(2) * 1.0);
            }
        });

        sql =
                cube.getDbms().equals("oracle") ?
                        "with t1 as (select timestamp as timest from OLAPsession where session_id = \"" + sessionid + "\" and value_en = 'read' and rownum <=1 order by 1 desc), "
                                + "t2 as (select timestamp as timest from OLAPsession where session_id = \"" + sessionid + "\" and value_en = 'navigate' and rownum <=1 order by 1 desc), "
                                + "t3 as (select timestamp as timest from OLAPsession, t2 where session_id = \"" + sessionid + "\" and value_en <> 'navigate' and timestamp >= t2.timest and rownum <=1 order by 1 asc) "
                                + "select count(*) as steps, count(distinct case when annotation_id is null or annotation_id = '' then null else annotation_id end) "
                                + "from OLAPsession, t1, t2, t3 "
                                + "where session_id = \"" + sessionid + "\" and value_en <> 'navigate' and timestamp > t1.timest and timestamp <= t3.timest"
                        :
                        "select count(*) as steps, count(distinct case when annotation_id is null or annotation_id = '' then null else annotation_id end) "
                                + "from OLAPsession "
                                + "where session_id = \"" + sessionid + "\" and `value_en` <> \"navigate\" and"
                                + "    `timestamp` > (select `timestamp` from OLAPsession where session_id = \"" + sessionid + "\" and value_en = \"read\" order by 1 desc limit 1) and "
                                + "    `timestamp` <= (select `timestamp` from olapsession where session_id = \"" + sessionid + "\" and value_en <> \"navigate\" and `timestamp` >= (select `timestamp` from olapsession where session_id = \"" + sessionid + "\" and value_en = \"navigate\" order by 1 desc limit 1) order by 1 asc limit 1)";
        DBmanager.executeMetaQuery(cube, sql, res -> {
            while (res.next()) {
                statistics.put("fullquery_iterations", res.getInt(1) * 1.0);
                statistics.put("fullquery_ambiguities", res.getInt(2) * 1.0);
                statistics.put("operator_iterations", (statistics.get("session_iterations") - res.getInt(1)) / 2.0);
                statistics.put("operator_ambiguities", (statistics.get("session_ambiguities") - res.getInt(2)) / 2.0);
            }
        });
        return statistics;
    }
}