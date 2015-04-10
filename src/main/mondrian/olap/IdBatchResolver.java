package mondrian.olap;

import mondrian.mdx.*;
import org.apache.commons.collections.*;
import org.apache.log4j.Logger;

import java.util.*;

import static org.apache.commons.collections.CollectionUtils.filter;

/**
 * Spike class which collects and  resolve identifiers in groups of children
 * where possible.  For example, if an enumerated set within an MDX
 * query includes references to 10 stores under the parent
 *   [USA].[CA].[San Francisco]
 * the class will attempt to identify those 10 identifiers up front
 * and issue a single lookup, resulting in fewer and more efficient
 * SQL queries.
 * The resulting collection of resolved identifiers is returned in a
 * map of <QueryPart, QueryPart>, where the unresolved Exp object acts
 * as the key.
 *
 * This class makes no assurances that all identifiers will be resolved.
 *
 */
public final class IdBatchResolver {
    static final Logger LOGGER = Logger.getLogger(IdBatchResolver.class);

    private final Query query;
    private final Formula[] formulas;
    private final QueryAxis[] axes;
    private final Cube cube;
    private final Collection<String> dimensionNames = new ArrayList<String>();
    private final Collection<String> hierarchyNames = new ArrayList<String>();
    private final Collection<String> levelNames = new ArrayList<String>();

    private  SortedSet<Id> identifiers = new TreeSet<Id>(
        new IdComparator());

    public IdBatchResolver(Query query) {
        this.query = query;
        formulas = query.getFormulas();
        axes = query.getAxes();
        cube = query.getCube();
        initOlapElementNames();
    }

    public void initOlapElementNames() {
        dimensionNames.addAll(getOlapElementNames(cube.getDimensions()));
        for(Dimension dim : cube.getDimensions()) {
            hierarchyNames.addAll(getOlapElementNames(dim.getHierarchies()));
            for(Hierarchy hier: dim.getHierarchies()) {
                levelNames.addAll(getOlapElementNames(hier.getLevels()));
            }
        }
    }


    public Map<QueryPart, QueryPart> resolve() {
        retrieveIdentifiers();
        return lookupInParentGroupings(identifiers);
       // return Collections.emptyMap();
    }

    private void retrieveIdentifiers() {
        MdxVisitor identifierVisitor = new IdentifierVisitor(identifiers);
        for (QueryAxis axis : axes) {
            axis.accept(identifierVisitor);
        }
        for (Formula formula : formulas) {
            formula.accept(identifierVisitor);
        }
        expandIdentifiers(identifiers);
    }

    /**
     *  Loops through the SortedSet of Ids, attempting to load sets of
     *  children of parent Ids.
     *  The loop below assumes the the SortedSet is ordered by segment
     *  size from smallest to largest, such that parent identifiers will
     *  occur before their children.
     */
    private  Map<QueryPart, QueryPart> lookupInParentGroupings(
        SortedSet<Id> identifiers)
    {
        final Map<QueryPart, QueryPart> resolvedIdentifiers =
            new HashMap<QueryPart, QueryPart>();

        while(identifiers.size() > 0) {
            Id parent = identifiers.first();
            identifiers.remove(parent);

            if (!supportedIdentifier(parent)) {
                continue;
            }
            Exp exp = (Exp)resolvedIdentifiers.get( parent );
            // for single segment identifiers, compare to list of dimensions / hierarchies
            if (exp == null) {
                try {
                    exp = Util.lookup(query, parent.getSegments(), false);
                } catch (Exception exception) {
                    LOGGER.debug(
                        String.format(
                            "Failed to resolve '%s' during batch ID "
                                + "resolution.  "
                                + "This can happen if a parent member is role "
                                + "restricted and is not necessarily an error.",
                            parent));
                }
                resolvedIdentifiers.put(parent, (QueryPart)exp);
            }
            Member parentMember = getMemberFromExp(exp);
            if (parentMember == null
                || parentMember.equals(
                parentMember.getHierarchy().getNullMember())
                || parentMember.isMeasure())
            {
                // couldn't associate this Id with a member.  Move on.
                continue;
            }
            final List<Id> children = findChildIds(parent, identifiers);
            final List<Id.NameSegment> segmentLists =
                collectChildrenNameSegments(parentMember, children);

            if (segmentLists.size() > 0) {
                List<Member> childMembers =
                    lookupChildrenByNames(parentMember, segmentLists);
                addChildrenToResolvedMap(
                    resolvedIdentifiers, children, childMembers);
            }
        }
        return resolvedIdentifiers;
    }

    private void addChildrenToResolvedMap(
        Map<QueryPart, QueryPart> resolvedIdentifiers, List<Id> children,
        List<Member> childMembers) {
        for (Member child : childMembers) {
            for (Id childId : children) {
                List<Id.Segment> segment = childId.getSegments();
                if (!resolvedIdentifiers.containsKey(childId)
                    && segment.get(segment.size() - 1)
                    .matches(child.getName())) {
                    resolvedIdentifiers.put(childId,
                        (QueryPart)Util.createExpr(child));
                }
            }
        }
    }

    private List<Member> lookupChildrenByNames(
        Member parentMember,
        List<Id.NameSegment> segmentLists)
    {
        try {
            return query.getSchemaReader(true)
                .lookupMemberChildrenByNames(parentMember,
                    segmentLists, MatchType.EXACT);
        } catch (Exception e) {
            LOGGER.info(
                String.format(
                    "Failure while looking up children of '%s' during  "
                    + "batch member resolution.  Child member refs:  %s",
                    parentMember,
                    Arrays.toString(segmentLists.toArray())), e);
        }
        // don't want to fail at this point.  Member resolution still has
        // another chance to succeed.
        return Collections.emptyList();
    }

    /**
     * Filters the children list to those that contain identifiers
     * we think we can batch resolve, then transforms the Id list
     * to the corresponding NameSegment.
     */
    private List<Id.NameSegment> collectChildrenNameSegments(
        final Member parentMember, List<Id> children)
    {
        filter(children, new Predicate() {
            // remove children we can't support
                @Override
                public boolean evaluate(Object o) {
                    Id id = (Id)o;
                    return !Util.matches(parentMember, id.getSegments())
                        && supportedIdentifier(id);
                }
            });
        return new ArrayList(
            CollectionUtils.collect(children, new Transformer() {
                // convert the collection to a list of NameSegments
            @Override
            public Object transform(Object o) {
                Id id = (Id)o;
                return getLastSegment(id);
            }
        }));
    }

    private Id.Segment getLastSegment(Id id) {
        int segSize = id.getSegments().size();
        return id.getSegments().get(segSize - 1);
    }

    /**
     * If the identifier is a "shortcut" reference (a single segment)
     * that doesn't refer to a dimension or hierarchy, we're not going
     * to try to resolve it.  Our chance of batching effectively is low.
     * Also if it it might be a formula reference we'll play it safe
     * and skip it.
     */
    private boolean supportedIdentifier(Id id) {
        return (isPossibleMemberRef(id))
            && !idIsCalcMember(id)
            && !id.getSegments().get(0).matches("Measures")
            && getLastSegment(id) instanceof Id.NameSegment;
    }

    /**
     * Returns the [All] member from HierarchyExpr and DimensionExpr
     * associated with hieararchies that have an All member.
     * Returns the member associated with a MemberExpr.
     * For all other Exp returns null.
     */
    private Member getMemberFromExp(Exp exp) {
        if (exp instanceof DimensionExpr) {
            Hierarchy hier = ((DimensionExpr)exp)
                .getDimension().getHierarchy();
            if (hier.hasAll()) {
                return hier.getAllMember();
            }
        } else if (exp instanceof HierarchyExpr) {
            Hierarchy hier = ((HierarchyExpr)exp)
                .getHierarchy();
            if (hier.hasAll()) {
                return hier.getAllMember();
            }
        } else if (exp instanceof MemberExpr) {
            return ((MemberExpr)exp).getMember();
        }
        return null;
    }



    public Collection<String> getOlapElementNames(
        OlapElement[] olapElements)
    {
        return CollectionUtils.collect(
            Arrays.asList(olapElements),
            new Transformer() {
                @Override
                public Object transform(Object o) {
                    return ((OlapElement)o).getName();
                }
            });
    }

    /**
     * Returns true if the Id is something that will potentially translate into
     * either the All/Default member of a dimension/hierarchy,
     * or a specific member.
     * This filters out references that we'd be unlikely to effectively
     * handle.
     */
    private boolean isPossibleMemberRef(Id id) {
        int size = id.getSegments().size();

        //todo  need to deal w/ ssas naming and dim.hier
        if (size == 1) {
            Id.Segment seg = id.getSegments().get(0);
            return segMatchInNames(seg, dimensionNames)
                || segMatchInNames(seg, hierarchyNames);
        }
        if (segMatchInNames(getLastSegment(id), levelNames)) {
            // conservative.  false on any match of any level name
            return false;
        }
        // don't support "shortcut" member references references
        return size > 1;

//        for (Dimension dim : cube.getDimensions()) {
//            if (size == 1
//                && id.getSegments().get(0).matches(dim.getName())) {
//                return true;
//            }
//            for (Hierarchy hier : dim.getHierarchies()) {
//                //TODO or size = 2 (ssas naming) or [dim.hier]
//                if (size == 1
//                    && id.getSegments().get(0).matches(hier.getName())) {
//                    return true;
//                }
//                for (Level level : hier.getLevels()) {
//
//                    if (id.getSegments().get(size-1)
//                        .matches(level.getName())) {
//                        // conservative.  false on any match of any level name
//                        return false;
//                    }
//                }
//            }
//        }
//        // don't support "shortcut" member references references
//        return size > 1;
    }

    private boolean segMatchInNames(Id.Segment seg,
                                    Collection<String> names) {
        for (String name : names) {
            if (seg.matches(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Conservative check that returns true if any formula
     * has the same last segment as the target identifier.
     * Can give false positives if an identifier matches a formula
     * name under a different parent.
     */
    private boolean idIsCalcMember(Id checkId) {
        return query.getSchemaReader(false)
            .getCalculatedMember(checkId.getSegments()) != null;
    }

    private List<Id> findChildIds(Id parent, SortedSet<Id> identifiers) {
        List<Id> childIds = new ArrayList<Id>();
        for (Id id : identifiers) {
            final List<Id.Segment> idSeg = id.getSegments();
            final List<Id.Segment> parentSegments = parent.getSegments();
            final int parentSegSize = parentSegments.size();
            if (idSeg.size() == parentSegSize +1
                && parent.getSegments().equals(idSeg.subList(0, parentSegSize))) {
                childIds.add(id);
            }
        }

        return childIds;
    }

    /**
     * Adds each parent segment to the set.
     */
    private void expandIdentifiers(Set<Id> identifiers) {
        Set<Id> expandedIdentifiers = new HashSet<Id>();
        for (Id id : identifiers) {
            for (int i = 1; i < id.getSegments().size(); i++) {
                expandedIdentifiers.add(new Id(id.getSegments().subList(0,i )));
            }
        }
        identifiers.addAll(expandedIdentifiers);
    }

    /**
     * Sorts shorter segments first, then by string compare.
     * This allows processing parents first during the lookup loop,
     * which is required by the algorithm.
     */
    private static class IdComparator implements Comparator<Id> {
        public int compare(Id o1, Id o2) {
            List<Id.Segment> o1Seg = o1.getSegments();
            List<Id.Segment> o2Seg = o2.getSegments();

            if (o1Seg.size() > o2Seg.size()) {
                return 1;
            } else if (o1Seg.size() < o2Seg.size()) {
                return -1;
            } else {
                return o1Seg.toString()
                    .compareTo(o2Seg.toString());
            }
        }
    }
}
