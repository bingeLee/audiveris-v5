//----------------------------------------------------------------------------//
//                                                                            //
//                        G l y p h I n s p e c t o r                         //
//                                                                            //
//  Copyright (C) Herve Bitteur 2000-2009. All rights reserved.               //
//  This software is released under the GNU General Public License.           //
//  Please contact users@audiveris.dev.java.net to report bugs & suggestions. //
//----------------------------------------------------------------------------//
//
package omr.glyph;

import omr.constant.ConstantSet;

import omr.log.Logger;

import omr.score.common.PixelRectangle;

import omr.sheet.Scale;
import omr.sheet.SystemInfo;

import omr.util.Implement;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Class <code>GlyphInspector</code> is at a System level, dedicated to the
 * inspection of retrieved glyphs, their recognition being usually based on
 * features used by a neural network evaluator.
 *
 * @author Herv&eacute; Bitteur
 * @version $Id$
 */
public class GlyphInspector
{
    //~ Static fields/initializers ---------------------------------------------

    /** Specific application parameters */
    private static final Constants constants = new Constants();

    /** Usual logger utility */
    private static final Logger logger = Logger.getLogger(GlyphInspector.class);

    //~ Instance fields --------------------------------------------------------

    /** Dedicated system */
    private final SystemInfo system;

    /** Related scale */
    private final Scale scale;

    /** Related lag */
    private final GlyphLag lag;

    /** Constants for alter verification */
    final int maxCloseStemDx;
    final int    minCloseStemOverlap;
    final int    maxCloseStemLength;
    final int    maxNaturalOverlap;
    final int    maxSharpNonOverlap;
    final double alterMaxDoubt;

    //~ Constructors -----------------------------------------------------------

    //----------------//
    // GlyphInspector //
    //----------------//
    /**
     * Create an GlyphInspector instance.
     *
     * @param system the dedicated system
     */
    public GlyphInspector (SystemInfo system)
    {
        this.system = system;
        scale = system.getSheet()
                      .getScale();
        lag = system.getSheet()
                    .getVerticalLag();

        maxCloseStemDx = scale.toPixels(constants.maxCloseStemDx);
        minCloseStemOverlap = scale.toPixels(constants.minCloseStemOverlap);
        maxCloseStemLength = scale.toPixels(constants.maxCloseStemLength);
        maxNaturalOverlap = scale.toPixels(constants.maxNaturalOverlap);
        maxSharpNonOverlap = scale.toPixels(constants.maxSharpNonOverlap);
        alterMaxDoubt = constants.alterMaxDoubt.getValue();
    }

    //~ Methods ----------------------------------------------------------------

    //--------------------//
    // getCleanupMaxDoubt //
    //--------------------//
    /**
     * Report the maximum doubt for a cleanup
     *
     *
     * @return maximum acceptable doubt value
     */
    public static double getCleanupMaxDoubt ()
    {
        return constants.cleanupMaxDoubt.getValue();
    }

    //-----------------//
    // getLeafMaxDoubt //
    //-----------------//
    /**
     * Report the maximum doubt for a leaf
     *
     *
     * @return maximum acceptable doubt value
     */
    public static double getLeafMaxDoubt ()
    {
        return constants.leafMaxDoubt.getValue();
    }

    //-------------------------//
    // getMinCompoundPartDoubt //
    //-------------------------//
    /**
     * Report the minimum doubt value to be considered as part of a compound
     * @return the doubt threshold for a compound part
     */
    public static double getMinCompoundPartDoubt ()
    {
        return constants.minCompoundPartDoubt.getValue();
    }

    //-------------------//
    // getSymbolMaxDoubt //
    //-------------------//
    /**
     * Report the maximum doubt for a symbol
     *
     * @return maximum acceptable doubt value
     */
    public static double getSymbolMaxDoubt ()
    {
        return constants.symbolMaxDoubt.getValue();
    }

    //----------------//
    // evaluateGlyphs //
    //----------------//
    /**
     * All unassigned symbol glyphs of a given system, for which we can get
     * a positive vote from the evaluator, are assigned the voted shape.
     */
    public void evaluateGlyphs (double maxDoubt)
    {
        Evaluator evaluator = GlyphNetwork.getInstance();

        for (Glyph glyph : system.getGlyphs()) {
            if (glyph.getShape() == null) {
                // Get vote
                Evaluation vote = evaluator.vote(glyph, maxDoubt);

                if (vote != null) {
                    glyph.setShape(vote.shape, vote.doubt);
                }
            }
        }
    }

    //---------------//
    // inspectGlyphs //
    //---------------//
    /**
     * Process the given system, by retrieving unassigned glyphs, evaluating
     * and assigning them if OK, or trying compounds otherwise.
     *
     * @param maxDoubt the maximum acceptable doubt for this processing
     */
    public void inspectGlyphs (double maxDoubt)
    {
        system.retrieveGlyphs();
        evaluateGlyphs(maxDoubt);
        system.removeInactiveGlyphs();
        retrieveCompounds(maxDoubt);
        evaluateGlyphs(maxDoubt);
    }

    //-------------------//
    // purgeManualShapes //
    //-------------------//
    /**
     * Purge a collection of glyphs from manually assigned shapes
     * @param glyphs the glyph collection to purge
     */
    public static void purgeManualShapes (Collection<Glyph> glyphs)
    {
        for (Iterator<Glyph> it = glyphs.iterator(); it.hasNext();) {
            Glyph glyph = it.next();

            if (glyph.isManualShape()) {
                it.remove();
            }
        }
    }

    //-------------//
    // tryCompound //
    //-------------//
    /**
     * Try to build a compound, starting from given seed and looking into the
     * collection of suitable glyphs.
     *
     * <p>Note that this method has no impact on the system/lag environment.
     * It is the caller's responsability, for a successful (i.e. non-null)
     * compound, to assign its shape and to add the glyph to the system/lag.
     *
     * @param seed the initial glyph around which the compound is built
     * @param suitables collection of potential glyphs
     * @param adapter the specific behavior of the compound tests
     * @return the compound built if successful, null otherwise
     */
    public Glyph tryCompound (Glyph           seed,
                              List<Glyph>     suitables,
                              CompoundAdapter adapter)
    {
        // Build box extended around the seed
        Rectangle   rect = seed.getContourBox();
        Rectangle   box = new Rectangle(
            rect.x - adapter.getBoxDx(),
            rect.y - adapter.getBoxDy(),
            rect.width + (2 * adapter.getBoxDx()),
            rect.height + (2 * adapter.getBoxDy()));

        // Retrieve good neighbors among the suitable glyphs
        List<Glyph> neighbors = new ArrayList<Glyph>();

        // Include the seed in the compound glyphs
        neighbors.add(seed);

        for (Glyph g : suitables) {
            if (!adapter.isSuitable(g)) {
                continue;
            }

            if (box.intersects(g.getContourBox())) {
                neighbors.add(g);
            }
        }

        if (neighbors.size() > 1) {
            if (logger.isFineEnabled()) {
                logger.finest(
                    "neighbors=" + Glyph.toString(neighbors) + " seed=" + seed);
            }

            Glyph compound = system.buildCompound(neighbors);

            if (adapter.isValid(compound)) {
                // If this compound duplicates an original glyph, 
                // make sure the shape was not forbidden in the original
                Glyph original = system.getSheet()
                                       .getVerticalLag()
                                       .getOriginal(compound);

                if ((original == null) ||
                    !original.isShapeForbidden(compound.getShape())) {
                    if (logger.isFineEnabled()) {
                        logger.fine("Inserted compound " + compound);
                    }

                    return compound;
                }
            }
        }

        return null;
    }

    //------------------//
    // verifyAlterSigns //
    //------------------//
    /**
     * Verify the case of stems very close to each other since they may result
     * from wrong segmentation of sharp and natural signs
     * @return the number of cases fixed
     */
    public int verifyAlterSigns ()
    {
        // First retrieve the collection of all stems in the system
        // Ordered naturally by their abscissa
        SortedSet<Glyph> stems = new TreeSet<Glyph>();

        for (Glyph glyph : system.getGlyphs()) {
            if (glyph.isStem() && glyph.isActive()) {
                PixelRectangle box = glyph.getContourBox();

                // Check stem length
                if (box.height <= maxCloseStemLength) {
                    stems.add(glyph);
                }
            }
        }

        int nb = 0; // Success counter

        // Then, look for close stems
        for (Glyph glyph : stems) {
            if (!glyph.isStem()) {
                continue;
            }

            PixelRectangle box = glyph.getContourBox();
            int            x = box.x + (box.width / 2);

            //logger.info("Checking stems close to glyph #" + glyph.getId());
            for (Glyph other : stems.tailSet(glyph)) {
                if ((other == glyph) || !other.isStem()) {
                    continue;
                }

                PixelRectangle oBox = other.getContourBox();
                int            oX = oBox.x + (oBox.width / 2);

                // Check horizontal distance
                int dx = oX - x;

                if (dx > maxCloseStemDx) {
                    break; // Since the set is ordered, no candidate is left
                }

                // Check vertical overlap
                int commonTop = Math.max(box.y, oBox.y);
                int commonBot = Math.min(
                    box.y + box.height,
                    oBox.y + oBox.height);
                int overlap = commonBot - commonTop;

                if (overlap < minCloseStemOverlap) {
                    continue;
                }

                logger.info(
                    "close stems: " +
                    Glyph.toString(Arrays.asList(glyph, other)));

                // "hide" the stems to not perturb evaluation
                glyph.setShape(null);
                other.setShape(null);

                boolean success = false;

                if (overlap <= maxNaturalOverlap) {
                    //                    logger.info(
                    //                        "Natural glyph rebuilt as #" + compound.getId());
                    //                    success = true;
                } else {
                    success = checkSharp(box, oBox);
                }

                if (success) {
                    nb++;
                } else {
                    // Restore stem shapes
                    glyph.setShape(Shape.COMBINING_STEM);
                    other.setShape(Shape.COMBINING_STEM);
                }
            }
        }

        return nb;
    }

    //------------//
    // checkSharp //
    //------------//
    /**
     * Check if, around the two (stem) boxes, there is actually a sharp sign
     * @param lbox contour box of left stem
     * @param rBox contour box of right stem
     * @return true if successful
     */
    private boolean checkSharp (PixelRectangle lBox,
                                PixelRectangle rBox)
    {
        final int lX = lBox.x + (lBox.width / 2);
        final int rX = rBox.x + (rBox.width / 2);
        final int dyTop = Math.abs(lBox.y - rBox.y);
        final int dyBot = Math.abs(
            (lBox.y + lBox.height) - rBox.y - rBox.height);

        if ((dyTop <= maxSharpNonOverlap) && (dyBot <= maxSharpNonOverlap)) {
            if (logger.isFineEnabled()) {
                logger.fine("SHARP sign?");
            }

            int            halfWidth = (3 * maxCloseStemDx) / 2;
            int            hMargin = minCloseStemOverlap / 2;
            PixelRectangle outerBox = new PixelRectangle(
                ((lX + rX) / 2) - halfWidth,
                Math.min(lBox.y, rBox.y) - hMargin,
                2 * halfWidth,
                Math.max(lBox.y + lBox.height, rBox.y + rBox.height) -
                Math.min(lBox.y, rBox.y) + (2 * hMargin));

            if (logger.isFineEnabled()) {
                logger.fine("outerBox: " + outerBox);
            }

            // Look for glyphs in this outer box
            Set<Glyph> glyphs = lag.lookupGlyphs(system.getGlyphs(), outerBox);
            purgeManualShapes(glyphs);

            Glyph compound = system.buildCompound(glyphs);
            system.computeGlyphFeatures(compound);

            Evaluation vote = GlyphNetwork.getInstance()
                                          .vote(compound, alterMaxDoubt);

            if (vote != null) {
                if (vote.shape == Shape.SHARP) {
                    compound = system.addGlyph(compound);
                    compound.setShape(vote.shape, Evaluation.ALGORITHM);
                    logger.info("Sharp glyph rebuilt as #" + compound.getId());

                    return true;
                } else {
                    logger.warning(
                        "Shape " + vote.shape + " better then sharp for " +
                        Glyph.toString(glyphs));
                }
            }
        }

        return false;
    }

    //-------------------//
    // retrieveCompounds //
    //-------------------//
    /**
     * In the specified system, look for glyphs portions that should be
     * considered as parts of compound glyphs
     */
    private void retrieveCompounds (double maxDoubt)
    {
        BasicAdapter adapter = new BasicAdapter(maxDoubt);

        // Collect glyphs suitable for participating in compound building
        List<Glyph>  suitables = new ArrayList<Glyph>(
            system.getGlyphs().size());

        for (Glyph glyph : system.getGlyphs()) {
            if (adapter.isSuitable(glyph)) {
                suitables.add(glyph);
            }
        }

        // Sort suitable glyphs by decreasing weight
        Collections.sort(
            suitables,
            new Comparator<Glyph>() {
                    public int compare (Glyph o1,
                                        Glyph o2)
                    {
                        return o2.getWeight() - o1.getWeight();
                    }
                });

        // Now process each seed in turn, by looking at smaller ones
        for (int index = 0; index < suitables.size(); index++) {
            Glyph seed = suitables.get(index);
            adapter.setSeed(seed);

            Glyph compound = tryCompound(
                seed,
                suitables.subList(index + 1, suitables.size()),
                adapter);

            if (compound != null) {
                compound = system.addGlyph(compound);
                compound.setShape(
                    adapter.getVote().shape,
                    adapter.getVote().doubt);
            }
        }
    }

    //~ Inner Interfaces -------------------------------------------------------

    //-----------------//
    // CompoundAdapter //
    //-----------------//
    /**
     * Interface <code>CompoundAdapter</code> provides the needed features for
     * a generic compound building.
     */
    public static interface CompoundAdapter
    {
        //~ Methods ------------------------------------------------------------

        /** Extension in abscissa to look for neighbors
         * @return the extension on left and right
         */
        int getBoxDx ();

        /** Extension in ordinate to look for neighbors
         * @return the extension on top and bottom
         */
        int getBoxDy ();

        /**
         * Predicate for a glyph to be a potential part of the building (the
         * location criteria is handled separately)
         * @param glyph the glyph to check
         * @return true if the glyph is suitable for inclusion
         */
        boolean isSuitable (Glyph glyph);

        /** Predicate to check the success of the newly built compound
         * @param compound the resulting compound glyph to check
         * @return true if the compound is found OK
         */
        boolean isValid (Glyph compound);
    }

    //~ Inner Classes ----------------------------------------------------------

    //--------------//
    // BasicAdapter //
    //--------------//
    /**
     * Class <code>BasicAdapter</code> is a CompoundAdapter meant to retrieve
     * all compounds (in a system). It is reusable from one candidate to the
     * other, by using the setSeed() method.
     */
    private class BasicAdapter
        implements CompoundAdapter
    {
        //~ Instance fields ----------------------------------------------------

        /** Maximum doubt for a compound */
        private final double maxDoubt;

        /** The seed being considered */
        private Glyph seed;

        /** The result of compound evaluation */
        private Evaluation vote;

        //~ Constructors -------------------------------------------------------

        public BasicAdapter (double maxDoubt)
        {
            this.maxDoubt = maxDoubt;
        }

        //~ Methods ------------------------------------------------------------

        @Implement(CompoundAdapter.class)
        public int getBoxDx ()
        {
            return scale.toPixels(constants.boxWiden);
        }

        @Implement(CompoundAdapter.class)
        public int getBoxDy ()
        {
            return scale.toPixels(constants.boxWiden);
        }

        public void setSeed (Glyph seed)
        {
            this.seed = seed;
        }

        @Implement(CompoundAdapter.class)
        public boolean isSuitable (Glyph glyph)
        {
            return glyph.isActive() &&
                   (!glyph.isKnown() ||
                   (!glyph.isManualShape() &&
                   ((glyph.getShape() == Shape.DOT) ||
                   (glyph.getShape() == Shape.SLUR) ||
                   (glyph.getShape() == Shape.CLUTTER) ||
                   (glyph.getDoubt() >= getMinCompoundPartDoubt()))));
        }

        @Implement(CompoundAdapter.class)
        public boolean isValid (Glyph compound)
        {
            vote = GlyphNetwork.getInstance()
                               .vote(compound, maxDoubt);

            if (vote != null) {
                compound.setShape(vote.shape, vote.doubt);
            }

            return (vote != null) && vote.shape.isWellKnown() &&
                   (vote.shape != Shape.CLUTTER) &&
                   (!seed.isKnown() || (vote.doubt < seed.getDoubt()));
        }

        public Evaluation getVote ()
        {
            return vote;
        }
    }

    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
        extends ConstantSet
    {
        //~ Instance fields ----------------------------------------------------

        Scale.Fraction   boxWiden = new Scale.Fraction(
            0.15,
            "Box widening to check intersection with compound");
        Evaluation.Doubt alterMaxDoubt = new Evaluation.Doubt(
            3,
            "Maximum doubt for alteration sign verifocation");
        Evaluation.Doubt cleanupMaxDoubt = new Evaluation.Doubt(
            1.2,
            "Maximum doubt for cleanup phase");
        Evaluation.Doubt leafMaxDoubt = new Evaluation.Doubt(
            1.01,
            "Maximum acceptance doubt for a leaf");
        Evaluation.Doubt symbolMaxDoubt = new Evaluation.Doubt(
            1.0001,
            "Maximum doubt for a symbol");
        Evaluation.Doubt minCompoundPartDoubt = new Evaluation.Doubt(
            1.020,
            "Minimum doubt for a suitable compound part");
        Scale.Fraction   maxCloseStemDx = new Scale.Fraction(
            0.7d,
            "Maximum horizontal distance for close stems");
        Scale.Fraction   maxSharpNonOverlap = new Scale.Fraction(
            1d,
            "Maximum vertical non overlap for sharp stems");
        Scale.Fraction   maxNaturalOverlap = new Scale.Fraction(
            1.5d,
            "Maximum vertical overlap for natural stems");
        Scale.Fraction   minCloseStemOverlap = new Scale.Fraction(
            0.5d,
            "Minimum vertical overlap for close stems");
        Scale.Fraction   maxCloseStemLength = new Scale.Fraction(
            3d,
            "Maximum length for close stems");
    }
}
