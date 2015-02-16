package mondrian.olap;

import mondrian.mdx.DimensionExpr;
import mondrian.mdx.HierarchyExpr;
import mondrian.mdx.MdxVisitor;
import mondrian.mdx.MemberExpr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;


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

    private final Query query;
    private final Formula[] formulas;
    private final QueryAxis[] axes;
    private final Cube cube;
    private  SortedSet<Id> identifiers = new TreeSet<Id>(
        new IdComparator());

    public IdBatchResolver(Query query) {
        this.query = query;
        formulas = query.getFormulas();
        axes = query.getAxes();
        cube = query.getCube();
    }

    public Map<QueryPart, QueryPart> resolve() {
        retrieveIdentifiers();
        return lookupInParentGroupings(identifiers);
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
                exp = Util.lookup(query, parent.getSegments(), false);
                resolvedIdentifiers.put(parent, (QueryPart)exp);
            }
            Member parentMember = getMemberFromExp(exp);
            if (parentMember == null) {
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
        return query.getSchemaReader(true)
            .lookupMemberChildrenByNames(parentMember,
                segmentLists, MatchType.EXACT);
    }

    private List<Id.NameSegment> collectChildrenNameSegments(
        Member parentMember, List<Id> children)
    {
        List<Id.NameSegment> segmentLists = new ArrayList<Id.NameSegment>();
        for (Id id : children ) {
            if (id.toString().equalsIgnoreCase(parentMember.getUniqueName())) {
                // TODO not pretty, not right. Need a way
                // to avoid including [All] in list of children when
                // the parent is derived from a DimensionExpr or
                // HierarchyExpr.
                continue;

            }
            int segSize = id.getSegments().size();
            Id.Segment lastSeg = id.getSegments().get(segSize -1);
            if (lastSeg instanceof Id.NameSegment
                && supportedIdentifier(id)) {
                segmentLists.add((Id.NameSegment) lastSeg);
            }
        }
        return segmentLists;
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
            && !formulasMightContainId(id);
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


    /**
     * Returns true if the Id is something that will potentially translate into
     * either the All/Default member of a dimension/hierarchy,
     * or a specific member.
     * This filters out references that we'd be unlikely to effectively
     * handle.
     */
    private boolean isPossibleMemberRef(Id id) {
        int size = id.getSegments().size();
        for (Dimension dim : cube.getDimensions()) {
            if (size == 1
                && id.getSegments().get(0).matches(dim.getName())) {
                return true;
            }
            for (Hierarchy hier : dim.getHierarchies()) {
                if (size == 1
                    && id.getSegments().get(0).matches(hier.getName())) {
                    return true;
                }
                for (Level level : hier.getLevels()) {

                    if (id.getSegments().get(size-1)
                        .matches(level.getName())) {
                        // conservative.  false on any match of any level name
                        return false;
                    }
                }
            }
        }
        // don't support "shortcut" member references references
        return size > 1;
    }

    /**
     * Conservative check that returns true if any formula
     * has the same last segment as the target identifier.
     * Can give false positives if an identifier matches a formula
     * name under a different parent.
     */
    private boolean formulasMightContainId(Id checkId) {
        List<Id.Segment> checkSegment = checkId.getSegments();
        Id.Segment lastSeg = checkSegment.get(checkSegment.size() - 1);

        for(Formula formula : formulas) {

            List<Id.Segment> formulaSegments =
                formula.getIdentifier().getSegments();
            if (lastSeg.equals(
                formulaSegments.get(formulaSegments.size() - 1))
                ) {
                return true;
            }
        }
        return false;
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
