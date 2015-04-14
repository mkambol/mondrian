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

    // dimension and hierarchy unique names are collected during init
    // to assist in classifying Ids as potentially resolvable to members.
    private final Collection<String> dimensionUniqueNames =
        new ArrayList<String>();
    private final Collection<String> hierarchyUniqueNames =
        new ArrayList<String>();
    // level names are checked against the identifiers to avoid incorrectly
    // interpreting a Dimension.Level reference as Dimension.Member.
    private final Collection<String> levelNames =
        new ArrayList<String>();

    private  SortedSet<Id> identifiers = new TreeSet<Id>(
        new IdComparator());

    public IdBatchResolver(Query query) {
        this.query = query;
        formulas = query.getFormulas();
        axes = query.getAxes();
        cube = query.getCube();
        initOlapElementNames();
        initIdentifiers();
    }

    public void initOlapElementNames() {
        dimensionUniqueNames.addAll(getOlapElementNames(cube.getDimensions(), true));
        for(Dimension dim : cube.getDimensions()) {
            hierarchyUniqueNames.addAll(getOlapElementNames(dim.getHierarchies(), true));
            for(Hierarchy hier: dim.getHierarchies()) {
                levelNames.addAll(getOlapElementNames(hier.getLevels(), false));
            }
        }
    }

    /**
     * Attempts to resolve the identifiers contained in the query in
     * batches based on the parent, e.g. looking up and resolving the
     * states in the set:
     *   { [Store].[USA].[CA], [Store].[USA].[OR] }
     * together rather than individually.
     * Note that there is no guarantee that all identifiers will be
     * resolved.  Calculated members, for example, are explicitly not
     * handled here.  The purpose of this class is to improve efficiency
     * of resolution of non-calculated members, but must be followed
     * by more thorough expression resolution.
     *
     * @return  a Map of the expressions Id elements mapped to their
     * respective resolved Exp.
     */
    public Map<QueryPart, QueryPart> resolve() {
        return resolveInParentGroupings(identifiers);
    }

    private void initIdentifiers() {
        MdxVisitor identifierVisitor = new IdentifierVisitor(identifiers);
        for (QueryAxis axis : axes) {
            axis.accept(identifierVisitor);
        }
        if (query.getSlicerAxis() != null) {
            query.getSlicerAxis().accept(identifierVisitor);
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
    private  Map<QueryPart, QueryPart> resolveInParentGroupings(
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
            if (exp == null) {
                exp = lookupExp(resolvedIdentifiers, parent, exp);
            }
            Member parentMember = getMemberFromExp(exp);
            if (supportedMember(parentMember))
            {
                continue;
            }
            batchResolveChildren(
                parent, parentMember, identifiers, resolvedIdentifiers);
        }
        return resolvedIdentifiers;
    }

    /**
     * Find the children of Id parent in the identfiers set and resolves
     * all supported children together, adding them to the resolvedIdentifiers
     * map.
     */
    private void batchResolveChildren(
        Id parent, Member parentMember, SortedSet<Id> identifiers,
        Map<QueryPart, QueryPart> resolvedIdentifiers)
    {

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

    private Exp lookupExp(
        Map<QueryPart, QueryPart> resolvedIdentifiers,
        Id parent,
        Exp exp)
    {
        try {
//            exp = Util.createExpr(
//                query.getSchemaReader(true)
//                .lookupCompound(query.getCube(),
//                    parent.getSegments(), false,Category.Unknown ));
            exp = Util.lookup(query, parent.getSegments(), false);
        } catch (Exception exception) {
            LOGGER.info(
                String.format(
                    "Failed to resolve '%s' during batch ID "
                    + "resolution.",
                    parent));
        }
        resolvedIdentifiers.put(parent, (QueryPart)exp);
        return exp;
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
        Id.Segment seg = getLastSegment(id);
        if (!(seg instanceof Id.NameSegment)) {
            return false;
        }
        return (isPossibleMemberRef(id))
            && !segmentIsCalcMember(id.getSegments())
            && !id.getSegments().get(0).matches("Measures");
    }

    private boolean supportedMember(Member member) {
        return member == null
            || member.equals(
            member.getHierarchy().getNullMember())
            || member.isMeasure();
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



    private Collection<String> getOlapElementNames(
        OlapElement[] olapElements, final boolean uniqueName)
    {
        return CollectionUtils.collect(
            Arrays.asList(olapElements),
            new Transformer() {
                @Override
                public Object transform(Object o) {
                    return uniqueName ? ((OlapElement)o).getUniqueName()
                        : ((OlapElement)o).getName();
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

        if (size == 1) {
            //Id.Segment seg = id.getSegments().get(0);
            return segListMatchInNames(id.getSegments(), dimensionUniqueNames)
                || segListMatchInNames(id.getSegments(), hierarchyUniqueNames);
        }
        if (MondrianProperties.instance().SsasCompatibleNaming.get()
            && size == 2) {
            return segListMatchInNames(
                id.getSegments(), hierarchyUniqueNames);

        }
        if (segMatchInNames(getLastSegment(id), levelNames)) {
            // conservative.  false on any match of any level name
            return false;
        }
        // don't support "shortcut" member references references
        return size > 1;
    }

    private boolean segListMatchInNames(List<Id.Segment> segments, Collection<String> names) {
        String segUniqueName = Util.implode(segments);
        for (String name : names) {
           if (Util.equalName(segUniqueName, name)) {
               return true;
           }
        }
        return false;
    }

    private boolean segMatchInNames(
        Id.Segment seg, Collection<String> names)
    {
        for (String name : names) {
            if (seg.matches(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean segmentIsCalcMember(final List<Id.Segment> checkSegments) {
        return CollectionUtils
            .exists(Arrays.asList(formulas),
                new Predicate() {
                    @Override
                    public boolean evaluate(Object o) {
                        Formula formula = (Formula)o;
                        List<Id.Segment> segments = formula.getIdentifier()
                            .getSegments();
                        if (segments.size() != checkSegments.size()) {
                            return false;
                        }
                        if (segments.equals(checkSegments)) {
                            return true;
                        }
                        // wasn't directly equal, let's try segment
                        // at a time to make sure case diff, etc.
                        // is accounted for
                        for (int i = 0; i < segments.size(); i++) {
                            if (!(checkSegments.get(i) instanceof
                                Id.NameSegment)) {
                                return false;
                            }
                            String name = ((Id.NameSegment)
                                checkSegments.get(i)).getName();
                            if (!segments.get(i).matches(name)) {
                                return false;
                            }
                        }
                        return true;
                }});
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
