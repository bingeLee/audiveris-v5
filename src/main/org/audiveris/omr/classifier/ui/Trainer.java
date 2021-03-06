//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                         T r a i n e r                                          //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Hervé Bitteur and others 2000-2017. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.classifier.ui;

import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;

import org.audiveris.omr.classifier.Classifier;
import org.audiveris.omr.classifier.ShapeClassifier;
import org.audiveris.omr.constant.ConstantManager;
import org.audiveris.omr.ui.OmrGui;
import org.audiveris.omr.ui.util.Panel;
import org.audiveris.omr.ui.util.UILookAndFeel;
import org.audiveris.omr.ui.util.UIUtil;

import org.jdesktop.application.Application;
import org.jdesktop.application.SingleFrameApplication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Locale;
import java.util.Observable;

import javax.swing.JFrame;

/**
 * Class {@code Trainer} handles a User Interface dedicated to the
 * training and testing of a glyph classifier.
 * <p>
 * The frame is divided vertically in 3 parts:
 * <ol>
 * <li>The selection in repository of samples ({@link SelectionPanel})
 * <li>The training of the neural network classifier ({@link TrainingPanel})
 * <li>The validation of the neural network classifier ({@link ValidationPanel})
 * </ol>
 * This class can be launched as a stand-alone program.
 *
 * @author Hervé Bitteur
 */
public class Trainer
        extends SingleFrameApplication
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(Trainer.class);

    /** The single instance of this class. */
    private static volatile Trainer INSTANCE;

    /** Stand-alone run (vs part of Audiveris). */
    private static boolean standAlone = false;

    /** Standard width for labels in DLUs. */
    static final String LABEL_WIDTH = "50dlu";

    /** Standard width for fields/buttons in DLUs. */
    static final String FIELD_WIDTH = "30dlu";

    /** An adapter triggered on window closing. */
    private static final WindowAdapter windowCloser = new WindowAdapter()
    {
        @Override
        public void windowClosing (WindowEvent e)
        {
            // Store latest constant values
            ConstantManager.getInstance().storeResource();

            // That's all folks !
            System.exit(0);
        }
    };

    //~ Instance fields ----------------------------------------------------------------------------
    /** Related frame. */
    private JFrame frame;

    /** Panel for selection in repository. */
    private final SelectionPanel selectionPanel;

    /** Panel for Neural network training. */
    private final TrainingPanel trainingPanel;

    /** Panel for train set validation. */
    private final ValidationPanel trainValidationPanel;

    /** Panel for train set validation. */
    private final ValidationPanel testValidationPanel;

    /** Current task. */
    final Task task = new Task();

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Create an instance of Glyph Trainer (there should be just one)
     */
    public Trainer ()
    {
        // Create the companions
        selectionPanel = new SelectionPanel(task);
        trainingPanel = new TrainingPanel(task, selectionPanel);

        final Classifier classifier = ShapeClassifier.getInstance();
        trainValidationPanel = new ValidationPanel(task, classifier, selectionPanel, true);
        testValidationPanel = new ValidationPanel(task, classifier, selectionPanel, false);

        // Initial state
        task.setActivity(Task.Activity.INACTIVE);

        // Specific ending if stand alone
        if (!standAlone) {
            frame = defineLayout(new JFrame());
        } else {
            INSTANCE = this;
        }
    }

    //~ Methods ------------------------------------------------------------------------------------
    //-------------//
    // getInstance //
    //-------------//
    public static synchronized Trainer getInstance ()
    {
        if (INSTANCE == null) {
            INSTANCE = new Trainer();
        }

        return INSTANCE;
    }

    //--------//
    // launch //
    //--------//
    /**
     * (Re)activate the trainer tool
     */
    public static void launch ()
    {
        if (standAlone) {
        } else {
            final JFrame frame = getInstance().frame;
            OmrGui.getApplication().show(frame);
            UIUtil.unMinimize(frame);
        }
    }

    //------//
    // main //
    //------//
    /**
     * Just to allow stand-alone running of this class
     *
     * @param args not used
     */
    public static void main (String... args)
    {
        standAlone = true;

        // Set UI Look and Feel
        UILookAndFeel.setUI(null);
        Locale.setDefault(Locale.ENGLISH);

        // Off we go...
        Application.launch(Trainer.class, args);
    }

    //------------//
    // initialize //
    //------------//
    @Override
    protected void initialize (String[] args)
    {
        logger.debug("Trainer. 1/initialize");
    }

    //-------//
    // ready //
    //-------//
    @Override
    protected void ready ()
    {
        logger.debug("Trainer. 3/ready");

        frame.addWindowListener(windowCloser);
    }

    //---------//
    // startup //
    //---------//
    @Override
    protected void startup ()
    {
        logger.debug("Trainer. 2/startup");

        frame = defineLayout(getMainFrame());

        show(frame); // Here we go...
    }

    //--------------//
    // displayFrame //
    //--------------//
    void displayFrame ()
    {
        frame.toFront();
    }

    //--------------//
    // defineLayout //
    //--------------//
    /**
     * Define the layout of components within the provided frame.
     *
     * @param frame the bare frame
     * @return the populated frame
     *
     */
    private JFrame defineLayout (final JFrame frame)
    {
        frame.setName("TrainerFrame"); // For SAF life cycle

        /*
         * +=============================================================+
         * | . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . |
         * | . Selection . . . . . . . . . . . . . . . . . . . . . . . . |
         * | . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . |
         * |-------------------------------------------------------------|
         * | . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . |
         * | . Training. . . . . . . . . . . . . . . . . . . . . . . . . |
         * | . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . |
         * |-------------------------------------------------------------|
         * | . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . |
         * | . Validation [train set]. . . . . . . . . . . . . . . . . . |
         * | . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . |
         * |-------------------------------------------------------------|
         * | . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . |
         * | . Validation [test set] . . . . . . . . . . . . . . . . . . |
         * | . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . |
         * +=============================================================+
         */
        final String panelInterline = Panel.getPanelInterline();
        FormLayout layout = new FormLayout(
                "pref",
                "pref," + panelInterline + "," + "pref," + panelInterline + "," + "pref,"
                + panelInterline + "," + "pref");

        CellConstraints cst = new CellConstraints();
        PanelBuilder builder = new PanelBuilder(layout, new Panel());

        int r = 1; // --------------------------------
        builder.add(selectionPanel.getComponent(), cst.xy(1, r));

        r += 2; // --------------------------------
        builder.add(trainingPanel.getComponent(), cst.xy(1, r));

        r += 2; // --------------------------------
        builder.add(trainValidationPanel.getComponent(), cst.xy(1, r));

        r += 2; // --------------------------------
        builder.add(testValidationPanel.getComponent(), cst.xy(1, r));

        frame.add(builder.getPanel());

//        // Resource injection
//        ResourceMap resource = OmrGui.getApplication().getContext().getResourceMap(getClass());
//        resource.injectComponents(frame);
//
        Classifier classifier = ShapeClassifier.getInstance();
        frame.setTitle(classifier.getName());

        return frame;
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //------//
    // Task //
    //------//
    /**
     * Class {@code Task} handles which activity is currently being carried
     * out, only one being current at any time.
     */
    static class Task
            extends Observable
    {
        //~ Enumerations ---------------------------------------------------------------------------

        /**
         * Enum {@code Activity} defines the possible activities in
         * training.
         */
        static enum Activity
        {
            //~ Enumeration constant initializers --------------------------------------------------

            /** No ongoing activity */
            INACTIVE,
            /** Selecting samples */
            SELECTION,
            /** Training on samples */
            TRAINING,
            /** Validating classifier */
            VALIDATION;
        }

        //~ Instance fields ------------------------------------------------------------------------
        /** Current activity. */
        private Activity activity = Activity.INACTIVE;

        //~ Methods --------------------------------------------------------------------------------
        //-------------//
        // getActivity //
        //-------------//
        /**
         * Report the current training activity
         *
         * @return current activity
         */
        public Activity getActivity ()
        {
            return activity;
        }

        //-------------//
        // setActivity //
        //-------------//
        /**
         * Assign a new current activity and notify all observers
         *
         * @param activity
         */
        public void setActivity (Activity activity)
        {
            this.activity = activity;
            setChanged();
            notifyObservers();
        }
    }
}
