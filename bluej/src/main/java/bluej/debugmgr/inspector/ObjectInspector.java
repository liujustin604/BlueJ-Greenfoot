/*
 This file is part of the BlueJ program. 
 Copyright (C) 1999-2010,2011,2013,2014,2016,2017,2018,2019,2021,2023,2025  Michael Kolling and John Rosenberg

 This program is free software; you can redistribute it and/or 
 modify it under the terms of the GNU General Public License 
 as published by the Free Software Foundation; either version 2 
 of the License, or (at your option) any later version. 

 This program is distributed in the hope that it will be useful, 
 but WITHOUT ANY WARRANTY; without even the implied warranty of 
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the 
 GNU General Public License for more details. 

 You should have received a copy of the GNU General Public License 
 along with this program; if not, write to the Free Software 
 Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA. 

 This file is subject to the Classpath exception as provided in the  
 LICENSE.txt file that accompanied this code.
 */
package bluej.debugmgr.inspector;

import java.lang.reflect.Modifier;
import java.util.*;

import com.sun.jdi.StringReference;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.stage.Screen;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import bluej.Config;
import bluej.debugger.DebuggerField;
import bluej.debugger.DebuggerObject;
import bluej.debugger.gentype.GenTypeClass;
import bluej.pkgmgr.Package;
import bluej.pkgmgr.PackageEditor;
import bluej.testmgr.record.ArrayElementGetRecord;
import bluej.testmgr.record.ArrayElementInspectorRecord;
import bluej.testmgr.record.GetInvokerRecord;
import bluej.testmgr.record.InvokerRecord;
import bluej.testmgr.record.ObjectInspectInvokerRecord;
import bluej.utility.DialogManager;
import bluej.utility.JavaNames;
import bluej.utility.javafx.JavaFXUtil;
import threadchecker.OnThread;
import threadchecker.Tag;

/**
 * A window that displays the fields in an object or a method return value.
 * 
 * @author Michael Kolling
 * @author Poul Henriksen
 * @author Bruce Quig
 */
@OnThread(Tag.FXPlatform)
public class ObjectInspector extends Inspector
{
    // === static variables ===

    protected final static String inspectTitle = Config.getString("debugger.inspector.object.title");
    protected final static String noFieldsMsg = Config.getString("debugger.inspector.object.noFields");
    protected final static String numFields = Config.getString("debugger.inspector.numFields");
    public static final int CORNER_SIZE = 40;

    // === instance variables ===

    /** A reference to the object being inspected */
    protected DebuggerObject obj;

    /**
     * Name of the object, as it appears on the object bench, or null if the
     * object being inspected is not on the object bench
     */
    protected String objName;

    protected boolean queryArrayElementSelected = false;
    private int selectedIndex;

    /**
     * List of indexes from a large array that have been selected for viewing:
     * extraArrayIndexesToShow is the list of array indexes that are not shown in the UI ([...]) but explicitly requested by the user
     * extraArraySlotIndex is a request array index (slot) by the user
     * extraArraySlotIndex is the corresponding index of the requested array index (extraArraySlotIndex) in the UI list,
     *                     as a large array is truncated, the index of the array and the index in the UI list may be different
     */
    private List<Integer> extraArrayIndexesToShow = new ArrayList<>();
    private int extraArrayIndexInList = -1;
    private int extraArraySlotIndex = -1;

    /**
     * list which is built when viewing an array that records the object slot
     * corresponding to each array index
     */
    protected List<Integer> indexToSlotList = null;
    private StackPane stackPane;
    private final Text stringContentDisplay = new Text();

    /**
     *  Note: 'pkg' may be null if 'ir' is null.
     * 
     * @param obj
     *            The object displayed by this viewer
     * @param name
     *            The name of this object or "null" if the name is unobtainable
     * @param pkg
     *            The package all this belongs to
     * @param ir
     *            the InvokerRecord explaining how we created this result/object
     *            if null, the "get" button is permanently disabled
     * @param parent
     *            The parent frame of this frame
     */
    public ObjectInspector(DebuggerObject obj, InspectorManager inspectorManager, String name, Package pkg, InvokerRecord ir, final Window parent)
    {
        super(inspectorManager, pkg, ir, StageStyle.TRANSPARENT);

        this.obj = obj;
        this.objName = name;

        makeFrame();
        update();

        setMinWidth(500);
        setMinHeight(260);

        if (parent instanceof Inspector) {
            setX(parent.getX() + 40);
            setY(parent.getY() + 40);
        }
        else {
            //DialogManager.centreWindow(thisInspector, parent);
        }

        installListenersForMoveDrag(20.0);
    }

    /**
     * Build the GUI
     */
    protected void makeFrame()
    {
        // Create the header

        Pane header = new VBox();
        GenTypeClass objType = obj.getGenType();
        String className = objType != null ? objType.toString(true) : "";

        String fullTitle = null;
        if(objName != null) {
            fullTitle = objName + " : " + className;
            setTitle(inspectTitle + " - " + objName + ", " + className + " " + numFields + " " + getListData().size());
        }
        else {
            fullTitle = " : " + className;
            setTitle(inspectTitle);
        }
        Label headerLabel = new Label(fullTitle);
        header.getChildren().add(headerLabel);
        //header.getChildren().add(new Separator(Orientation.HORIZONTAL));

        // Create the main panel (field list, Get/Inspect buttons)

        BorderPane mainPanel = new BorderPane();
        mainPanel.setCenter(fieldList);

        fieldList.setPlaceHolderText("  " + noFieldsMsg);

        mainPanel.setRight(createInspectAndGetButtons());

        // create bottom button pane with "Close" button

        Pane bottomPanel = new VBox();

        BorderPane buttonPanel = new BorderPane();
        Button button = createCloseButton();
        buttonPanel.setRight(button);
        Button classButton = new Button(showClassLabel);
        classButton.setOnAction(e -> showClass());
        buttonPanel.setLeft(classButton);

        Button copyButton = new Button(Config.getString("debugger.inspector.copyToClipboard"));
        copyButton.setOnAction(e -> {
            if (obj.getObjectReference() instanceof StringReference sr)
            {
                Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT, sr.toString()));
            }
        });
        buttonPanel.setCenter(copyButton);

        bottomPanel.getChildren().add(buttonPanel);

        BorderPane additionalPanel = new BorderPane();
        additionalPanel.getStyleClass().add("inspector-string-panel");
        Label stringHeaderLabel = new Label(Config.getString("debugger.inspector.stringContent"));
        additionalPanel.setTop(stringHeaderLabel);
        BorderPane.setAlignment(stringHeaderLabel, Pos.TOP_CENTER);
        BorderPane.setMargin(stringHeaderLabel, new Insets(0, 0, 5, 0));
        ScrollPane stringContentScroll = new ScrollPane(stringContentDisplay);
        stringContentScroll.getStyleClass().add("inspector-string-scroll");
        stringContentScroll.setMaxHeight(200);
        additionalPanel.setCenter(stringContentScroll);
        additionalPanel.visibleProperty().bind(stringContentDisplay.visibleProperty());
        additionalPanel.managedProperty().bind(additionalPanel.visibleProperty());
        copyButton.visibleProperty().bind(stringContentDisplay.visibleProperty());
        copyButton.managedProperty().bind(copyButton.visibleProperty());
        stringContentDisplay.setWrappingWidth(400);


        // add the components
        Pane contentPane = new VBox();
        contentPane.setBackground(null);

        contentPane.getChildren().addAll(header, mainPanel, additionalPanel, bottomPanel);
        VBox.setVgrow(mainPanel, Priority.ALWAYS);

        JavaFXUtil.addStyleClass(contentPane, "inspector", "inspector-object");
        JavaFXUtil.addStyleClass(header, "inspector-object-header", "inspector-header");


        button.setDefaultButton(true);
        stackPane = new StackPane(new ObjectBackground(CORNER_SIZE, new ReadOnlyDoubleWrapper(3.0)), contentPane);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(stackPane.widthProperty());
        clip.heightProperty().bind(stackPane.heightProperty());
        clip.setArcWidth(CORNER_SIZE);
        clip.setArcHeight(CORNER_SIZE);
        stackPane.setClip(clip);
        stackPane.setBackground(null);
        BorderPane root = new BorderPane(stackPane);
        root.setBackground(null);
        // Stop inspectors with arrays from being taller than the screen:
        root.setMaxHeight(Screen.getScreens().stream().mapToDouble(s -> s.getBounds().getHeight() - 150).max().orElse(1000));
        Scene scene = new Scene(root);
        scene.setFill(null);
        setScene(scene);
    }

    @Override
    public Region getContent()
    {
        return stackPane;
    }

    @Override
    protected boolean shouldAutoUpdate()
    {
        return Config.isGreenfoot();
    }

    @Override
    @OnThread(Tag.FXPlatform)
    public void update()
    {
        super.update();
        if (Objects.equals(obj.getClassName(), "java.lang.String") && obj.getObjectReference() instanceof StringReference sr)
        {
            stringContentDisplay.setText(sr.value());
            stringContentDisplay.setVisible(true);
        }
        else
        {
            stringContentDisplay.setVisible(false);
        }
    }

    /**
     * True if this inspector is used to display a method call result.
     */
    @Override
    @OnThread(Tag.FXPlatform)
    protected List<FieldInfo> getListData()
    {
        // if is an array (we potentially will compress the array if it is
        // large)
        if (obj.isArray()) {
            return compressArrayList(obj);
        }
        else {
            List<DebuggerField> fields = obj.getFields();
            List<FieldInfo> fieldInfos = new ArrayList<FieldInfo>(fields.size());
            for (DebuggerField field : fields) {
                if (! Modifier.isStatic(field.getModifiers())) {
                    String desc = Inspector.fieldToString(field);
                    String value = field.getValueString();
                    fieldInfos.add(new FieldInfo(desc, value));
                }
            }
            return fieldInfos;
        }
    }

    /**
     * An element in the field list was selected.
     */
    protected void listElementSelected(int slot)
    {
        if (slot == -1)
        {
            setCurrentObj(null, null, null);
            setButtonsEnabled(false, false);
            return;
        }

        // add index to slot method for truncated arrays
        if (obj.isArray()) {
            slot = indexToSlot(slot);
            if (slot >= 0) {
                selectedIndex = slot;
            }
            // if selection is the first field containing array length
            // we treat as special case and do nothing more
            if (slot == ARRAY_LENGTH_SLOT_VALUE) {
                setCurrentObj(null, null, null);
                setButtonsEnabled(false, false);
                return;
            }

            queryArrayElementSelected = (slot == (ARRAY_QUERY_SLOT_VALUE));
            // for array compression..
            if (queryArrayElementSelected) { // "..." in Array inspector
                setCurrentObj(null, null, null); //  selected
                if (! obj.getElementType().isPrimitive()) {
                    setButtonsEnabled(true, false);
                }
                else {
                    setButtonsEnabled(false, false);
                }
            }
            else {
                if (!obj.getElementType().isPrimitive()) {
                    DebuggerObject elementObj = obj.getElementObject(slot);
                    if (! elementObj.isNullObject()) {
                        setCurrentObj(elementObj, "[" + slot + "]", obj.getElementType().toString());
                        setButtonsEnabled(true, true);
                        return;
                    }
                }

                // primitive or null
                setCurrentObj(null, null, null);
                setButtonsEnabled(false, false);
            }

            return;
        }

        // Non-array
        DebuggerField field = obj.getInstanceField(slot);
        if (field != null && field.isReferenceType() && ! field.isNull()) {
            setCurrentObj(field.getValueObject(null), field.getName(), field.getType().toString());

            if (Modifier.isPublic(field.getModifiers())) {
                setButtonsEnabled(true, true);
            }
            else {
                boolean canGet = false;
                if (! Modifier.isPrivate(field.getModifiers())) {
                    // If the field is package-private and we are in the right package,
                    // we'll allow the get operation:
                    String fieldPkg = JavaNames.getPrefix(field.getDeclaringClassName());
                    String pkgName = (pkg == null) ? "" : pkg.getQualifiedName();
                    canGet = fieldPkg.equals(pkgName);
                }
                setButtonsEnabled(true, canGet);
            }
        }
        else {
            setCurrentObj(null, null, null);
            setButtonsEnabled(false, false);
        }
    }

    /**
     * Show the inspector for the class of an object.
     */
    protected void showClass()
    {
        inspectorManager.getClassInspectorInstance(obj.getClassRef(), pkg, this, null);
    }

    @Override
    protected void doInspect()
    {
        if (queryArrayElementSelected) {
            selectArrayElement();
        }
        else if (selectedField != null) {
            boolean isPublic = !getButton.isDisable();

            if (! obj.isArray()) {
                InvokerRecord newIr = new ObjectInspectInvokerRecord(selectedFieldName, ir);
                inspectorManager.getInspectorInstance(selectedField, selectedFieldName, pkg, isPublic ? newIr : null, this, null);
            }
            else {
                InvokerRecord newIr = new ArrayElementInspectorRecord(ir, selectedIndex);
                inspectorManager.getInspectorInstance(selectedField, selectedFieldName, pkg, isPublic ? newIr : null, this, null);
            }
        }
    }

    @Override
    protected void doGet()
    {
        if (selectedField != null) {
            InvokerRecord getIr;
            if (! obj.isArray()) {
                getIr = new GetInvokerRecord(selectedFieldType, selectedFieldName, ir);
            }
            else {
                getIr = new ArrayElementGetRecord(selectedFieldType, selectedIndex, ir);
            }

            DebuggerObject selField = this.selectedField;
            PackageEditor pkgEd = pkg.getEditor();
            pkgEd.recordInteraction(getIr);

            pkgEd.raisePutOnBenchEvent(this, selField, selField.getGenType(), getIr, true, Optional.empty());
        }
    }

    /**
     * Remove this inspector.
     */
    protected void remove()
    {
        if(inspectorManager != null) {
            inspectorManager.removeInspector(obj);
        }
    }

    /**
     * Shows a dialog to select array element for inspection
     */
    private void selectArrayElement()
    {
        String response = DialogManager.askStringFX(this, "ask-index");
        if (response != null) {
            try {
                int slot = Integer.parseInt(response);
                // check if within bounds of array
                if (slot >= 0 && slot < obj.getElementCount()) {
                    // if its an object set as current object
                    if (! obj.getElementType().isPrimitive() && ! obj.getElementObject(slot).isNullObject()) {
                        boolean isPublic = !getButton.isDisable();
                        InvokerRecord newIr = new ArrayElementInspectorRecord(ir, slot);
                        setCurrentObj(obj.getElementObject(slot), "[" + slot + "]", obj.getElementType().toString());
                        inspectorManager.getInspectorInstance(selectedField, selectedFieldName, pkg,
                                isPublic ? newIr : null, this, null);
                    }
                    else {
                        // It is not an object - a primitive, we update the array list display,
                        // we add this element as an "extra" index to show (unless it is already there, then just select
                        extraArraySlotIndex = slot; // set the slot index to be able to retrieve it's position in the list
                        if (!extraArrayIndexesToShow.contains(slot))
                        {
                            extraArrayIndexesToShow.add(slot);
                            // The call to update will redraw the list, which will set a value for extraArrayIndexInList
                            // that informs of the actual position of the request slot in the UI list.
                            update();
                            // There is a strange behaviour with ScrollPane being overwriting our scroll values (after
                            // the selection has been made). The only way we found to get through it is to call the helper
                            // JavaFXUtil.runAfterNextLayout twice (once doesn't work)
                            JavaFXUtil.runAfterNextLayout(this.getScene(), () -> {
                                JavaFXUtil.runAfterNextLayout(this.getScene(), () -> {
                                    // As we redraw the containers, we need to refresh the selection to make sure it can be shown properly
                                    if (fieldList != null)
                                    {
                                        int currentSelectIndex = fieldList.selectedIndexProperty().get();
                                        fieldList.select(-1);
                                        fieldList.select(currentSelectIndex);
                                    }
                                });
                            });
                        }
                        else 
                        {
                            // Retrieve the right index in the list
                            extraArrayIndexInList = indexToSlotList.indexOf(extraArraySlotIndex);
                        }

                        // Select the right row (as we have [...], the actual index and slot index maybe different, we need it find it)
                        // Make sure graphics are refreshed by changing the selection back and forth:
                        fieldList.select(-1);
                        fieldList.select(extraArrayIndexInList);

                        //reset the indexes indicators
                        extraArraySlotIndex = -1;
                        extraArrayIndexInList = -1;
                    }
                }
                else { // not within array bounds
                    DialogManager.showErrorFX(this, "out-of-bounds");
                }
            }
            catch (NumberFormatException e) {
                // input could not be parsed, eg. non integer value
                setCurrentObj(null, null, null);
                DialogManager.showErrorFX(this, "cannot-access-element");
            }
        }
        else {
            // set current object to null to avoid re-inspection of
            // previously selected wildcard
            setCurrentObj(null, null, null);
        }
    }

    private final static int VISIBLE_ARRAY_START = 40; // show at least the
                                                       // first 40 elements
    private final static int VISIBLE_ARRAY_TAIL = 5; // and the last five
                                                     // elements

    private final static int ARRAY_QUERY_SLOT_VALUE = -2; // signal marker of
                                                          // the [...] slot in
                                                          // our
    private final static int ARRAY_LENGTH_SLOT_VALUE = -1; // marker for having
                                                           // selected the slot
                                                           // containing array
                                                           // length

    /**
     * Compress a potentially large array into a more displayable shortened
     * form.
     * 
     * Compresses an array field name list to a maximum of VISIBLE_ARRAY_START
     * which are guaranteed to be displayed at the start, then some [..]
     * expansion slots, followed by VISIBLE_ARRAY_TAIL elements from the end of
     * the array. When a selected element is chosen indexToSlot allows the
     * selection to be converted to the original array element position.
     * 
     * When an element from the [...] section is queried, we had it inside the list
     * (depending on the index, we can have more than [...] eventually.
     * 
     * @param arrayObject
     *            the full field list for an array
     * @return the compressed array
     */
    private List<FieldInfo> compressArrayList(DebuggerObject arrayObject)
    {
        // mimic the public length field that arrays possess
        // according to the java spec...
        indexToSlotList = new LinkedList<Integer>();
        indexToSlotList.add(0, Integer.valueOf(ARRAY_LENGTH_SLOT_VALUE));

        int arrayTotalLength = arrayObject.getElementCount();

        // the +1 here is due to the fact that if we do not have at least one
        // more than
        // the sum of start elements and tail elements, then there is no point
        // in displaying
        // the ... elements because there would be no elements for them to
        // reveal
        if (arrayTotalLength > (VISIBLE_ARRAY_START + VISIBLE_ARRAY_TAIL + extraArrayIndexesToShow.size() + 2))
        {
            // the destination list
            List<FieldInfo> newArray = new ArrayList<FieldInfo>(2 + VISIBLE_ARRAY_START + VISIBLE_ARRAY_TAIL);
            newArray.add(0, new FieldInfo("int length", "" + arrayTotalLength));
            for (int i = 0; i <= VISIBLE_ARRAY_START; i++)
            {
                // first 40 elements are displayed as per normal
                newArray.add(new FieldInfo("[" + i + "]", arrayObject.getElementValueString(i)));
                indexToSlotList.add(i);
                //set the list position index for the request slot
                if(i == extraArraySlotIndex)
                {
                    extraArrayIndexInList = newArray.size() - 1;
                }
            }

            // now the first of our expansion slots unless when an extra queried value was requested
            boolean hasExpansion = false;
            for(int i = VISIBLE_ARRAY_START + 1; i < arrayTotalLength - VISIBLE_ARRAY_TAIL; i++)
            {
                if(extraArrayIndexesToShow.contains(i))
                {
                    //add a normal field
                    newArray.add(new FieldInfo("[" + i + "]", arrayObject.getElementValueString(i)));
                    indexToSlotList.add(i);
                    hasExpansion = false;
                    //set the list position index for the request slot
                    if(i == extraArraySlotIndex)
                    {
                        extraArrayIndexInList = newArray.size() - 1;
                    }
                }
                else if(!hasExpansion)
                {
                    newArray.add(new FieldInfo("[...]", ""));
                    indexToSlotList.add(Integer.valueOf(ARRAY_QUERY_SLOT_VALUE));
                    hasExpansion = true;
                }
            }

            // tail part
            for (int i = VISIBLE_ARRAY_TAIL; i > 0; i--)
            {
                // last 5 elements are displayed
                int elNum = arrayTotalLength - i;
                newArray.add(new FieldInfo("[" + elNum + "]", arrayObject.getElementValueString(elNum)));
                indexToSlotList.add(arrayTotalLength - i);
                //set the list position index for the request slot
                if((arrayTotalLength-i) == extraArraySlotIndex)
                {
                    extraArrayIndexInList = newArray.size() - 1;
                }
            }
            return newArray;
        }
        else {
            List<FieldInfo> fullArrayFieldList = new ArrayList<FieldInfo>(arrayObject.getElementCount() + 1);
            fullArrayFieldList.add(0, new FieldInfo("int length", "" + arrayObject.getElementCount()));

            for (int i = 0; i < arrayObject.getElementCount(); i++) {
                fullArrayFieldList.add(new FieldInfo("[" + i + "]", arrayObject.getElementValueString(i)));
                indexToSlotList.add(i);
            }
            return fullArrayFieldList;
        }
    }

    /**
     * Converts list index position to that of array element position in arrays.
     * Uses the List built in compressArrayList to do the mapping.
     * 
     * @param listIndexPosition
     *            the position selected in the list
     * @return the translated index of field array element
     */
    private int indexToSlot(int listIndexPosition)
    {
        Integer slot = indexToSlotList.get(listIndexPosition);

        return slot.intValue();
    }

    protected int getPreferredRows()
    {
        return 8;
    }
}
