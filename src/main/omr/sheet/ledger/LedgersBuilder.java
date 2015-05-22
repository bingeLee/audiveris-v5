//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                   L e d g e r s B u i l d e r                                  //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright © Hervé Bitteur and others 2000-2014. All rights reserved.
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.sheet.ledger;

import omr.check.Check;
import omr.check.CheckBoard;
import omr.check.CheckSuite;
import omr.check.Checkable;
import omr.check.Failure;

import omr.constant.Constant;
import omr.constant.ConstantSet;

import omr.glyph.GlyphLayer;
import omr.glyph.Shape;
import omr.glyph.facets.Glyph;

import omr.lag.Section;

import omr.math.GeoUtil;
import omr.math.LineUtil;
import static omr.run.Orientation.*;

import omr.selection.GlyphEvent;
import omr.selection.MouseMovement;
import omr.selection.UserEvent;

import omr.sheet.Picture;
import omr.sheet.Scale;
import omr.sheet.Sheet;
import omr.sheet.Staff;
import omr.sheet.SystemInfo;
import omr.sheet.SystemManager;
import omr.sheet.grid.Filament;
import omr.sheet.grid.FilamentsFactory;
import omr.sheet.grid.LineInfo;
import omr.sheet.ui.SheetAssembly;
import omr.sheet.ui.SheetTab;

import omr.sig.GradeImpacts;
import omr.sig.SIGraph;
import omr.sig.SIGraph.ReductionMode;
import omr.sig.inter.AbstractBeamInter;
import omr.sig.inter.Inter;
import omr.sig.inter.LedgerInter;
import omr.sig.relation.Exclusion;

import omr.util.HorizontalSide;
import static omr.util.HorizontalSide.*;
import omr.util.Predicate;

import ij.process.ByteProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Class {@code LedgersBuilder} retrieves ledgers for a system.
 * <p>
 * Each virtual line of ledgers is processed, one after the other, going away from the reference
 * staff, above and below:
 * <ol>
 * <li>All acceptable candidate glyph instances for the current virtual line are translated into
 * LedgerInter instances with proper intrinsic grade.</li>
 * <li>Exclusions can be inserted because of abscissa overlap.</li>
 * <li>Finally, using grades, the collection of ledgers interpretations is reduced and only the
 * remaining ones are recorded as such in staff map.
 * They will be used as ordinate references when processing the next virtual line.</li>
 * </ol>
 *
 * @author Hervé Bitteur
 */
public class LedgersBuilder
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(LedgersBuilder.class);

    /** Events this entity is interested in. */
    private static final Class<?>[] eventClasses = new Class<?>[]{GlyphEvent.class};

    /** Failure codes */
    private static final Failure TOO_SHORT = new Failure("Hori-TooShort");

    private static final Failure TOO_THIN = new Failure("Hori-TooThin");

    private static final Failure TOO_THICK = new Failure("Hori-TooThick");

    private static final Failure TOO_CONCAVE = new Failure("Hori-TooConcave");

    private static final Failure TOO_BENDED = new Failure("Hori-TooBended");

    private static final Failure TOO_SHIFTED = new Failure("Hori-TooShifted");

    //~ Instance fields ----------------------------------------------------------------------------
    //
    /** Related sheet. */
    private final Sheet sheet;

    /** Dedicated system. */
    private final SystemInfo system;

    /** Related sig. */
    private final SIGraph sig;

    /** Global sheet scale. */
    private final Scale scale;

    /** Check suite for short candidates. */
    private final LedgerSuite shortSuite = new LedgerSuite(false);

    /** Check suite for long candidates. */
    private final LedgerSuite longSuite = new LedgerSuite(true);

    /** The system-wide collection of ledger candidates. */
    private List<Glyph> ledgerCandidates;

    /** The (good) system-wide beams and hooks. */
    private List<Inter> systemBeams;

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * @param system the related system to process
     */
    public LedgersBuilder (SystemInfo system)
    {
        this.system = system;

        sig = system.getSig();
        sheet = system.getSheet();
        scale = sheet.getScale();
    }

    //~ Methods ------------------------------------------------------------------------------------
    //---------------//
    // addCheckBoard //
    //---------------//
    /**
     * Add a user board dedicated to ledger check.
     */
    public void addCheckBoard ()
    {
        SheetAssembly assembly = sheet.getAssembly();
        assembly.addBoard(SheetTab.DATA_TAB, new LedgerCheckBoard(sheet));
        assembly.addBoard(SheetTab.LEDGER_TAB, new LedgerCheckBoard(sheet));
    }

    //--------------//
    // buildLedgers //
    //--------------//
    /**
     * Search horizontal sticks for ledgers.
     */
    public void buildLedgers ()
    {
        try {
            // Put apart the (good) system beams
            systemBeams = getGoodBeams();

            // Retrieve system sections to provide to factory
            List<Section> sections = getCandidateSections();

            // Retrieve system candidate glyphs out of candidate sections
            ledgerCandidates = getCandidateGlyphs(sections);

            // Filter candidates accurately, line by line
            filterLedgers();
        } catch (Throwable ex) {
            logger.warn("Error retrieving ledgers. " + ex, ex);
        }
    }

    //-------------//
    // selectSuite //
    //-------------//
    /**
     * Select proper check suite, according to (length of) provided ledger candidate
     *
     * @param stick the candidate
     * @return the proper check suite to apply
     */
    public CheckSuite selectSuite (Glyph stick)
    {
        return (stick.getLength(HORIZONTAL) <= scale.toPixels(constants.maxShortLength))
                ? shortSuite : longSuite;
    }

    //-------------//
    // beamOverlap //
    //-------------//
    /**
     * Check whether stick middle point is contained by a good beam.
     *
     * @param stick the candidate to check
     * @return true if a beam overlap was detected
     */
    private boolean beamOverlap (Glyph stick)
    {
        Point2D middle = getMiddle(stick);

        for (Inter inter : systemBeams) {
            AbstractBeamInter beam = (AbstractBeamInter) inter;

            if (beam.getArea().contains(middle)) {
                if (stick.isVip() || logger.isDebugEnabled()) {
                    logger.info("ledger stick#{} overlaps beam#{}", stick.getId(), beam.getId());
                }

                return true;
            } else {
                // Speedup, since beams are sorted by abscissa
                if (beam.getBounds().getLocation().x > middle.getX()) {
                    return false;
                }
            }
        }

        return false;
    }

    //---------------//
    // filterLedgers //
    //---------------//
    /**
     * Use smart tests on ledger candidates.
     * Starting from each staff, check one interline higher (and lower) for candidates, etc.
     */
    private void filterLedgers ()
    {
        for (Staff staff : system.getStaves()) {
            logger.debug("Staff#{}", staff.getId());

            // Above staff
            for (int i = -1;; i--) {
                int count = lookupLine(staff, i);

                if (count == 0) {
                    break;
                }
            }

            // Below staff
            for (int i = 1;; i++) {
                int count = lookupLine(staff, i);

                if (count == 0) {
                    break;
                }
            }
        }
    }

    //--------------------//
    // getCandidateGlyphs //
    //--------------------//
    /**
     * Retrieve possible candidate glyph instances built from provided sections.
     *
     * @param sections the section population to build sticks from
     * @return a collection of candidate glyph instances
     */
    private List<Glyph> getCandidateGlyphs (List<Section> sections)
    {
        // Use filament factory
        FilamentsFactory factory = new FilamentsFactory(
                scale,
                sheet.getGlyphNest(),
                GlyphLayer.LEDGER,
                HORIZONTAL,
                Filament.class);

        // Adjust factory parameters
        final int maxThickness = Math.min(
                scale.toPixels(constants.maxThicknessHigh),
                scale.toPixels(constants.maxThicknessHigh2));
        factory.setMaxThickness(maxThickness);
        factory.setMinCoreSectionLength(constants.minCoreSectionLength);
        factory.setMaxOverlapDeltaPos(constants.maxOverlapDeltaPos);
        factory.setMaxCoordGap(constants.maxCoordGap);
        factory.setMaxPosGap(constants.maxPosGap);
        factory.setMaxOverlapSpace(constants.maxOverlapSpace);

        if (system.getId() == 1) {
            factory.dump("LedgersBuilder factory");
        }

        for (Section section : sections) {
            //TODO: this may prevent the processing of systems in parallel!???
            section.setGlyph(null); // Needed for sections "shared" by two systems!
        }

        List<Glyph> glyphs = factory.retrieveFilaments(sections);

        // Purge candidates that overlap good beams
        List<Glyph> toRemove = new ArrayList<Glyph>();

        for (Glyph glyph : glyphs) {
            if (beamOverlap(glyph)) {
                toRemove.add(glyph);
            }
        }

        if (!toRemove.isEmpty()) {
            glyphs.removeAll(toRemove);
        }

        // This is only meant to show them in specific color
        for (Glyph glyph : glyphs) {
            glyph.setShape(Shape.LEDGER_CANDIDATE);
        }

        return glyphs;
    }

    //----------------------//
    // getCandidateSections //
    //----------------------//
    /**
     * Retrieve good candidate sections.
     * These are sections from a (complete) horizontal lag that do not stand within staves and that
     * intersect a horizontal section.
     *
     * @return list of sections kept
     */
    private List<Section> getCandidateSections ()
    {
        List<Section> keptSections = new ArrayList<Section>();
        int minWidth = scale.toPixels(constants.minLedgerLengthLow);

        for (Section section : system.getLedgerSections()) {
            // Check minimum length
            if (section.getBounds().width < minWidth) {
                continue;
            }

            // Check this section intersects a horizontal section
            if (!intersectHorizontal(section)) {
                continue;
            }

            keptSections.add(section);
        }

        return keptSections;
    }

    //--------------//
    // getGoodBeams //
    //--------------//
    /**
     * Retrieve the list of beam / hook interpretations in the system, ordered by
     * abscissa.
     *
     * @return the sequence of system beams (full beams and hooks)
     */
    private List<Inter> getGoodBeams ()
    {
        List<Inter> beams = sig.inters(
                new Predicate<Inter>()
                {
                    @Override
                    public boolean check (Inter inter)
                    {
                        return (inter instanceof AbstractBeamInter) && inter.isGood();
                    }
                });

        Collections.sort(beams, Inter.byAbscissa);

        return beams;
    }

    //-----------------//
    // getLedgerTarget //
    //-----------------//
    /**
     * Report the line index and ordinate target for a candidate ledger, based on the
     * reference found (other ledger or staff line).
     *
     * @param stick the ledger candidate
     * @return the index value and target ordinate, or null if not found.
     */
    private IndexTarget getLedgerTarget (Glyph stick)
    {
        final Point center = stick.getCentroid();

        // Check possibles staves
        for (Staff staff : system.getStavesOf(center)) {
            // Search for best virtual line index
            double rawPitch = staff.pitchPositionOf(center);

            if (Math.abs(rawPitch) <= 4) {
                return null; // Point is within staff core height
            }

            int sign = (rawPitch > 0) ? 1 : (-1);
            int rawIndex = (int) Math.rint((Math.abs(rawPitch) - 4) / 2);
            int iMin = Math.max(1, rawIndex - 1);
            int iMax = rawIndex + 1;

            Integer bestIndex = null;
            Double bestTarget = null;
            double bestDy = Double.MAX_VALUE;
            double yMid = getMiddle(stick).getY();

            for (int i = iMin; i <= iMax; i++) {
                int index = i * sign;
                Double yRef = getYReference(staff, index, stick);

                if (yRef != null) {
                    double yTarget = yRef + (sign * scale.getInterline());
                    double dy = Math.abs(yTarget - yMid);

                    if (dy < bestDy) {
                        bestDy = dy;
                        bestIndex = index;
                        bestTarget = yTarget;
                    }
                }
            }

            if (bestIndex != null) {
                return new IndexTarget(bestIndex, bestTarget);
            }
        }

        return null;
    }

    //-----------//
    // getMiddle //
    //-----------//
    /**
     * Retrieve the middle point of a stick, assumed rather horizontal.
     *
     * @param stick the stick to process
     * @return the middle point
     */
    private static Point2D getMiddle (Glyph stick)
    {
        final Point2D startPoint = stick.getStartPoint(HORIZONTAL);
        final Point2D stopPoint = stick.getStopPoint(HORIZONTAL);

        return new Point2D.Double(
                (startPoint.getX() + stopPoint.getX()) / 2,
                (startPoint.getY() + stopPoint.getY()) / 2);
    }

    //---------------//
    // getYReference //
    //---------------//
    /**
     * Look for an ordinate reference suitable with provided stick.
     * This may be a ledger on the previous line or the staff line itself
     *
     * @param staff the staff being processed
     * @param index the position WRT to staff
     * @param stick the candidate stick to check
     * @return the ordinate reference found, or null if not found
     */
    private Double getYReference (Staff staff,
                                  int index,
                                  Glyph stick)
    {
        final int prevIndex = (index < 0) ? (index + 1) : (index - 1);

        if (prevIndex != 0) {
            final Set<LedgerInter> prevLedgers = staff.getLedgers(prevIndex);

            // If no previous ledger for reference, give up
            if ((prevLedgers == null) || prevLedgers.isEmpty()) {
                if (stick.isVip()) {
                    logger.info("Ledger candidate {} orphan", stick);
                }

                return null;
            }

            // Check abscissa compatibility
            Rectangle stickBox = stick.getBounds();

            for (LedgerInter ledger : prevLedgers) {
                if (ledger.getGlyph() == stick) {
                    // This may occur when manually using the ledger check
                    continue;
                }

                Rectangle ledgerBox = ledger.getBounds();

                if (GeoUtil.xOverlap(stickBox, ledgerBox) > 0) {
                    // Use this previous ledger as ordinate reference
                    double xMid = stick.getAreaCenter().x;
                    Glyph ledgerGlyph = ledger.getGlyph();

                    // Middle of stick may fall outside of ledger width
                    if (GeoUtil.xEmbraces(ledgerBox, xMid)) {
                        return ledgerGlyph.getLine().yAtX(xMid);
                    } else {
                        return LineUtil.intersectionAtX(
                                ledgerGlyph.getStartPoint(HORIZONTAL),
                                ledgerGlyph.getStopPoint(HORIZONTAL),
                                xMid).getY();
                    }
                }
            }

            if (stick.isVip()) {
                logger.info("Ledger candidate {} local orphan", stick);
            }

            return null;
        } else {
            // Use staff line as reference
            LineInfo staffLine = (index < 0) ? staff.getFirstLine() : staff.getLastLine();

            return staffLine.yAt(stick.getAreaCenter().getX());
        }
    }

    //---------------------//
    // intersectHorizontal //
    //---------------------//
    /**
     * Check whether the provided section intersects at least one horizontal section of
     * the system.
     *
     * @param section the provided (ledger) section
     * @return true if intersects a horizontal section
     */
    private boolean intersectHorizontal (Section section)
    {
        Rectangle sectionBox = section.getBounds();

        // Check this section intersects a horizontal section
        for (Section hs : system.getHorizontalSections()) {
            if (hs.getBounds().intersects(sectionBox)) {
                if (hs.intersects(section)) {
                    return true;
                }
            }
        }

        return false;
    }

    //------------//
    // lookupLine //
    //------------//
    /**
     * This is the heart of ledger retrieval, which looks for ledgers on a specific
     * "virtual line".
     * <p>
     * We use a very rough height for the region of interest, relying on pitch check WRT yTarget to
     * discard the too distant candidates.
     * However there is a risk that a ledger be found "acceptable" on two line indices.
     * Moreover, a conflict on line #2 could remove the ledger from SIG while it is still accepted
     * on line #1.
     *
     * @param staff the staff being processed
     * @param index index of line relative to staff
     * @return the number of ledgers found on this virtual line
     */
    private int lookupLine (Staff staff,
                            int index)
    {
        logger.debug("Checking staff: {} line: {}", staff.getId(), index);

        final int yMargin = scale.toPixels(constants.ledgerMarginY);
        final LineInfo staffLine = (index < 0) ? staff.getFirstLine() : staff.getLastLine();

        // Define bounds for the virtual line, properly shifted and enlarged
        Rectangle virtualLineBox = staffLine.getBounds();
        virtualLineBox.y += (index * scale.getInterline());
        virtualLineBox.grow(0, 2 * yMargin);

        final List<LedgerInter> ledgers = new ArrayList<LedgerInter>();

        // Filter enclosed candidates and populate acceptable ledgers
        for (Glyph stick : ledgerCandidates) {
            // Rough containment
            final Point2D middle = getMiddle(stick);

            if (!virtualLineBox.contains(middle)) {
                continue;
            }

            if (stick.isVip()) {
                logger.info("VIP lookupLine for {}", stick);
            }

            // Check for presence of ledger on previous line
            // and definition of a reference ordinate (staff line or ledger)
            final Double yRef = getYReference(staff, index, stick);

            if (yRef == null) {
                if (stick.isVip()) {
                    logger.info("VIP no line ref for {}", stick);
                }

                continue;
            }

            // Check precise vertical distance WRT the target ordinate
            final double yTarget = yRef + (Integer.signum(index) * scale.getInterline());

            // Choose which suite to apply
            CheckSuite suite = selectSuite(stick);

            GradeImpacts impacts = suite.getImpacts(new GlyphContext(stick, yTarget));
            double grade = impacts.getGrade();

            if (stick.isVip()) {
                logger.info("VIP staff#{} at {} {}", staff.getId(), index, impacts.getDump());
            }

            if (grade >= suite.getMinThreshold()) {
                stick = system.registerGlyph(stick); // Useful???

                // Sanity check
                Inter inter = sig.getInter(stick, LedgerInter.class);

                if (inter != null) {
                    logger.error("Double ledger definition {}", inter);
                }

                LedgerInter ledger = new LedgerInter(stick, impacts);
                ledger.setIndex(index);
                sig.addVertex(ledger);
                ledgers.add(ledger);
            }
        }

        if (!ledgers.isEmpty()) {
            // Now, check for collision within line population and reduce accordingly.
            reduceLedgers(staff, index, ledgers);

            // Populate staff with ledgers kept
            for (LedgerInter ledger : ledgers) {
                ledger.getGlyph().setShape(Shape.LEDGER); // Useful???
                staff.addLedger(ledger, index);

                if (ledger.isVip()) {
                    logger.info(
                            "VIP {} in staff#{} at {} for {}",
                            ledger,
                            staff.getId(),
                            index,
                            ledger.getDetails());
                }
            }
        }

        return ledgers.size();
    }

    //---------------//
    // reduceLedgers //
    //---------------//
    /**
     * Check for collision within line population of ledgers and reduce the population
     * accordingly.
     *
     * @param staff   staff being processed
     * @param index   index of virtual line around staff
     * @param ledgers population of ledger interpretations for the line
     */
    private void reduceLedgers (Staff staff,
                                int index,
                                List<LedgerInter> ledgers)
    {
        int maxDx = scale.toPixels(constants.maxInterLedgerDx);
        Set<Exclusion> exclusions = new LinkedHashSet<Exclusion>();
        Collections.sort(ledgers, Inter.byAbscissa);

        for (int i = 0; i < ledgers.size(); i++) {
            final LedgerInter ledger = ledgers.get(i);
            final Rectangle ledgerBox = ledger.getBounds();
            final Rectangle fatBox = ledger.getBounds();
            fatBox.grow(maxDx, scale.getInterline());

            // Check neighbors on the right only (since we are browsing a sorted list)
            for (LedgerInter other : ledgers.subList(i + 1, ledgers.size())) {
                if (GeoUtil.xOverlap(ledgerBox, other.getBounds()) > 0) {
                    // Abscissa overlap
                    exclusions.add(sig.insertExclusion(ledger, other, Exclusion.Cause.OVERLAP));
                } else {
                    break; // End of reachable neighbors
                }
            }
        }

        if (!exclusions.isEmpty()) {
            Set<Inter> deletions = sig.reduceExclusions(ReductionMode.STRICT, exclusions);
            logger.debug(
                    "Staff: {} index: {} deletions: {} {}",
                    staff.getId(),
                    index,
                    deletions.size(),
                    deletions);
            ledgers.removeAll(deletions);
        }
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
            extends ConstantSet
    {
        //~ Instance fields ------------------------------------------------------------------------

        Constant.Double maxSlopeForCheck = new Constant.Double(
                "slope",
                0.1,
                "Maximum slope for displaying check board");

        Constant.Double convexityLow = new Constant.Double(
                "end number",
                -0.5,
                "Minimum convexity ends");

        // Constants specified WRT mean line thickness
        // -------------------------------------------
        Scale.LineFraction maxOverlapDeltaPos = new Scale.LineFraction(
                1.5, //  1.0,
                "Maximum delta position between two overlapping filaments");

        Scale.LineFraction maxThicknessHigh = new Scale.LineFraction(
                3,
                "High Maximum thickness of an interesting stick (WRT staff line)");

        Scale.LineFraction maxThicknessLow = new Scale.LineFraction(
                1.0,
                "Low Maximum thickness of an interesting stick (WRT staff line)");

        // Constants specified WRT mean interline
        // --------------------------------------
        Scale.Fraction maxThicknessHigh2 = new Scale.Fraction(
                0.3,
                "High Maximum thickness of an interesting stick (WRT interline)");

        Scale.Fraction minCoreSectionLength = new Scale.Fraction(
                0.5,
                "Minimum length for a section to be considered as core");

        Scale.Fraction maxCoordGap = new Scale.Fraction(
                0.0,
                "Maximum abscissa gap between ledger filaments");

        Scale.Fraction maxPosGap = new Scale.Fraction(
                0.3, //0.2,
                "Maximum ordinate gap between ledger filaments");

        Scale.Fraction maxOverlapSpace = new Scale.Fraction(
                0.0,
                "Maximum space between overlapping filaments");

        Scale.Fraction ledgerMarginY = new Scale.Fraction(
                0.35,
                "Margin on ledger ordinate WRT theoretical ordinate");

        Scale.Fraction minLedgerLengthHigh = new Scale.Fraction(
                1.5,
                "High Minimum length for a ledger");

        Scale.Fraction minLedgerLengthLow = new Scale.Fraction(
                1.0,
                "Low Minimum length for a ledger");

        Scale.Fraction minThicknessHigh = new Scale.Fraction(
                0.25,
                "High Minimum thickness of an interesting stick");

        Scale.Fraction maxInterLedgerDx = new Scale.Fraction(
                2.5,
                "Maximum inter-ledger abscissa gap for ordinate compatibility test");

        Scale.Fraction maxInterLedgerDy = new Scale.Fraction(
                0.2,
                "Maximum inter-ledger ordinate gap");

        Scale.Fraction maxDistanceHigh = new Scale.Fraction(
                0.3,
                "Maximum average distance to straight line");

        Scale.Fraction maxShortLength = new Scale.Fraction(
                2.0,
                "Maximum length for 'short' ledgers");

        Constant.Ratio minWhiteLow = new Constant.Ratio(
                0.2,
                "Low Minimum for ratio of white pixels above or below ledger");

        Constant.Ratio minWhiteHigh = new Constant.Ratio(
                0.5,
                "High Minimum for ratio of white pixels above or below ledger");
    }

    //--------------//
    // GlyphContext //
    //--------------//
    private static class GlyphContext
            implements Checkable
    {
        //~ Instance fields ------------------------------------------------------------------------

        /** The stick being checked. */
        final Glyph stick;

        /** Target ordinate. */
        final double yTarget;

        //~ Constructors ---------------------------------------------------------------------------
        public GlyphContext (Glyph stick,
                             double yTarget)
        {
            this.stick = stick;
            this.yTarget = yTarget;
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void addFailure (Failure failure)
        {
            stick.addFailure(failure);
        }

        @Override
        public boolean isVip ()
        {
            return stick.isVip();
        }

        @Override
        public void setVip ()
        {
            stick.setVip();
        }

        @Override
        public String toString ()
        {
            return "stick#" + stick.getId();
        }
    }

    //-------------//
    // IndexTarget //
    //-------------//
    private static class IndexTarget
    {
        //~ Instance fields ------------------------------------------------------------------------

        final int index;

        final double target;

        //~ Constructors ---------------------------------------------------------------------------
        public IndexTarget (int index,
                            double target)
        {
            this.index = index;
            this.target = target;
        }
    }

    //------------------//
    // LedgerCheckBoard //
    //------------------//
    /**
     * A specific board to display intrinsic checks of ledger sticks.
     */
    private static class LedgerCheckBoard
            extends CheckBoard<GlyphContext>
    {
        //~ Instance fields ------------------------------------------------------------------------

        private final Sheet sheet;

        //~ Constructors ---------------------------------------------------------------------------
        public LedgerCheckBoard (Sheet sheet)
        {
            super("Ledger", null, sheet.getGlyphNest().getGlyphService(), eventClasses);
            this.sheet = sheet;
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void onEvent (UserEvent event)
        {
            try {
                // Ignore RELEASING
                if (event.movement == MouseMovement.RELEASING) {
                    return;
                }

                if (event instanceof GlyphEvent) {
                    GlyphEvent glyphEvent = (GlyphEvent) event;
                    Glyph glyph = glyphEvent.getData();

                    // Make sure we have a rather horizontal stick
                    if ((glyph != null)
                        && (Math.abs(glyph.getSlope()) <= constants.maxSlopeForCheck.getValue())) {
                        // Check if there is a staff line or ledger for reference
                        // For this we have to operate from some relevant system
                        SystemManager systemManager = sheet.getSystemManager();

                        for (SystemInfo system : systemManager.getSystemsOf(glyph)) {
                            LedgersBuilder builder = new LedgersBuilder(system);
                            IndexTarget it = builder.getLedgerTarget(glyph);

                            // Run the check suite?
                            if (it != null) {
                                applySuite(
                                        builder.selectSuite(glyph),
                                        new GlyphContext(glyph, it.target));

                                return;
                            }
                        }
                    }

                    tellObject(null);
                }
            } catch (Exception ex) {
                logger.warn(getClass().getName() + " onEvent error", ex);
            }
        }
    }

    //----------------//
    // ConvexityCheck //
    //----------------//
    private class ConvexityCheck
            extends Check<GlyphContext>
    {
        //~ Constructors ---------------------------------------------------------------------------

        public ConvexityCheck ()
        {
            super(
                    "Convex",
                    "Check number of convex stick ends",
                    constants.convexityLow,
                    Constant.Double.TWO,
                    true,
                    TOO_CONCAVE);
        }

        //~ Methods --------------------------------------------------------------------------------
        // Retrieve the density
        @Override
        protected double getValue (GlyphContext context)
        {
            ByteProcessor pixelFilter = sheet.getPicture().getSource(Picture.SourceKey.NO_STAFF);

            Glyph stick = context.stick;
            Rectangle box = stick.getBounds();
            int convexities = 0;

            // On each end of the stick, we check that pixels just above and
            // just below are white, so that stick slightly points out.
            // We use the stick bounds, regardless of the precise geometry inside.
            //
            //  X                                                         X
            //  +---------------------------------------------------------+
            //  |                                                         |
            //  |                                                         |
            //  +---------------------------------------------------------+
            //  X                                                         X
            //
            for (HorizontalSide hSide : HorizontalSide.values()) {
                int x = (hSide == LEFT) ? box.x : ((box.x + box.width) - 1);
                boolean topFore = pixelFilter.get(x, box.y - 1) == 0;
                boolean bottomFore = pixelFilter.get(x, box.y + box.height) == 0;
                boolean isConvex = !(topFore || bottomFore);

                if (isConvex) {
                    convexities++;
                }
            }

            return convexities;
        }
    }

    //-------------//
    // LedgerSuite //
    //-------------//
    private class LedgerSuite
            extends CheckSuite<GlyphContext>
    {
        //~ Constructors ---------------------------------------------------------------------------

        /**
         * Create a check suite.
         */
        public LedgerSuite (boolean isLong)
        {
            super("Ledger " + (isLong ? "long" : "short"));

            add(0.5, new MinThicknessCheck());
            add(0, new MaxThicknessCheck());
            add(4, new MinLengthCheck());
            add(2, new ConvexityCheck());
            add(1, new StraightCheck());
            add(0.5, new LeftPitchCheck());
            add(0.5, new RightPitchCheck());

            //            if (isLong) {
            //                add(1, new TopCheck());
            //                add(1, new BottomCheck());
            //            }
        }
    }

    //----------------//
    // LeftPitchCheck //
    //----------------//
    private class LeftPitchCheck
            extends Check<GlyphContext>
    {
        //~ Constructors ---------------------------------------------------------------------------

        protected LeftPitchCheck ()
        {
            super(
                    "LPitch",
                    "Check that left ordinate is close to theoretical value",
                    Constant.Double.ZERO,
                    constants.ledgerMarginY,
                    false,
                    TOO_SHIFTED);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        protected double getValue (GlyphContext context)
        {
            Glyph stick = context.stick;
            double yTarget = context.yTarget;
            double y = stick.getStartPoint(HORIZONTAL).getY();

            return sheet.getScale().pixelsToFrac(Math.abs(y - yTarget));
        }
    }

    //-------------------//
    // MaxThicknessCheck //
    //-------------------//
    private class MaxThicknessCheck
            extends Check<GlyphContext>
    {
        //~ Constructors ---------------------------------------------------------------------------

        protected MaxThicknessCheck ()
        {
            super(
                    "MaxTh.",
                    "Check that stick is not too thick",
                    constants.maxThicknessLow,
                    constants.maxThicknessHigh,
                    false,
                    TOO_THICK);
        }

        //~ Methods --------------------------------------------------------------------------------
        // Retrieve the thickness data
        @Override
        protected double getValue (GlyphContext context)
        {
            Glyph stick = context.stick;

            return sheet.getScale().pixelsToLineFrac(stick.getMeanThickness(HORIZONTAL));
        }
    }

    //----------------//
    // MinLengthCheck //
    //----------------//
    private class MinLengthCheck
            extends Check<GlyphContext>
    {
        //~ Constructors ---------------------------------------------------------------------------

        protected MinLengthCheck ()
        {
            super(
                    "Length",
                    "Check that stick is long enough",
                    constants.minLedgerLengthLow,
                    constants.minLedgerLengthHigh,
                    true,
                    TOO_SHORT);
        }

        //~ Methods --------------------------------------------------------------------------------
        // Retrieve the length data
        @Override
        protected double getValue (GlyphContext context)
        {
            Glyph stick = context.stick;

            return sheet.getScale().pixelsToFrac(stick.getLength(HORIZONTAL));
        }
    }

    //-------------------//
    // MinThicknessCheck //
    //-------------------//
    private class MinThicknessCheck
            extends Check<GlyphContext>
    {
        //~ Constructors ---------------------------------------------------------------------------

        protected MinThicknessCheck ()
        {
            super(
                    "MinTh.",
                    "Check that stick is thick enough",
                    Constant.Double.ZERO,
                    constants.minThicknessHigh,
                    true,
                    TOO_THIN);
        }

        //~ Methods --------------------------------------------------------------------------------
        // Retrieve the thickness data
        @Override
        protected double getValue (GlyphContext context)
        {
            Glyph stick = context.stick;

            return sheet.getScale().pixelsToFrac(stick.getMeanThickness(HORIZONTAL));
        }
    }

    //-----------------//
    // RightPitchCheck //
    //-----------------//
    private class RightPitchCheck
            extends Check<GlyphContext>
    {
        //~ Constructors ---------------------------------------------------------------------------

        protected RightPitchCheck ()
        {
            super(
                    "RPitch",
                    "Check that right ordinate is close to theoretical value",
                    Constant.Double.ZERO,
                    constants.ledgerMarginY,
                    false,
                    TOO_SHIFTED);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        protected double getValue (GlyphContext context)
        {
            Glyph stick = context.stick;
            double yTarget = context.yTarget;
            double y = stick.getStopPoint(HORIZONTAL).getY();

            return sheet.getScale().pixelsToFrac(Math.abs(y - yTarget));
        }
    }

    //---------------//
    // StraightCheck //
    //---------------//
    private class StraightCheck
            extends Check<GlyphContext>
    {
        //~ Constructors ---------------------------------------------------------------------------

        protected StraightCheck ()
        {
            super(
                    "Straight",
                    "Check that stick is rather straight",
                    Constant.Double.ZERO,
                    constants.maxDistanceHigh,
                    false,
                    TOO_BENDED);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        protected double getValue (GlyphContext context)
        {
            Glyph stick = context.stick;

            return sheet.getScale().pixelsToFrac(stick.getMeanDistance());
        }
    }
}
//    //-------------//
//    // BottomCheck //
//    //-------------//
//    private class BottomCheck
//            extends Check<GlyphContext>
//    {
//        //~ Constructors ---------------------------------------------------------------------------
//
//        protected BottomCheck ()
//        {
//            super(
//                    "Bottom",
//                    "Check that bottom of ledger touches some white pixels",
//                    constants.minWhiteLow,
//                    constants.minWhiteHigh,
//                    true,
//                    TOO_BLACK);
//        }
//
//        //~ Methods --------------------------------------------------------------------------------
//        @Override
//        protected double getValue (GlyphContext context)
//        {
//            Glyph stick = context.stick;
//            double sumFree = 0;
//
//            for (Section section : stick.getMembers()) {
//                Run run = section.getLastRun();
//                double free = (1 - section.getLastAdjacency()) * run.getLength();
//                sumFree += free;
//            }
//
//            return sumFree / stick.getLength(HORIZONTAL);
//        }
//    }
//    //----------//
//    // TopCheck //
//    //----------//
//    private class TopCheck
//            extends Check<GlyphContext>
//    {
//        //~ Constructors ---------------------------------------------------------------------------
//
//        protected TopCheck ()
//        {
//            super(
//                    "Top",
//                    "Check that top of ledger touches some white pixels",
//                    constants.minWhiteLow,
//                    constants.minWhiteHigh,
//                    true,
//                    TOO_BLACK);
//        }
//
//        //~ Methods --------------------------------------------------------------------------------
//        @Override
//        protected double getValue (GlyphContext context)
//        {
//            Glyph stick = context.stick;
//            double sumFree = 0;
//
//            for (Section section : stick.getMembers()) {
//                Run run = section.getFirstRun();
//                double free = (1 - section.getFirstAdjacency()) * run.getLength();
//                sumFree += free;
//            }
//
//            return sumFree / stick.getLength(HORIZONTAL);
//        }
//    }
//
//    private static final Failure TOO_BLACK = new Failure("Hori-TooBlack");