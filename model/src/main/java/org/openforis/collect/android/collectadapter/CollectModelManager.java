package org.openforis.collect.android.collectadapter;

import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.openforis.collect.android.CodeListService;
import org.openforis.collect.android.CoordinateDestinationService;
import org.openforis.collect.android.DefinitionProvider;
import org.openforis.collect.android.IdGenerator;
import org.openforis.collect.android.SurveyException;
import org.openforis.collect.android.attributeconverter.AttributeConverter;
import org.openforis.collect.android.gui.util.meter.Timer;
import org.openforis.collect.android.util.CoordinateUtils;
import org.openforis.collect.android.util.persistence.Database;
import org.openforis.collect.android.viewmodel.Definition;
import org.openforis.collect.android.viewmodel.UiAttribute;
import org.openforis.collect.android.viewmodel.UiAttributeCollection;
import org.openforis.collect.android.viewmodel.UiAttributeDefinition;
import org.openforis.collect.android.viewmodel.UiCode;
import org.openforis.collect.android.viewmodel.UiCodeAttribute;
import org.openforis.collect.android.viewmodel.UiCodeList;
import org.openforis.collect.android.viewmodel.UiCoordinateAttribute;
import org.openforis.collect.android.viewmodel.UiEntity;
import org.openforis.collect.android.viewmodel.UiEntityCollection;
import org.openforis.collect.android.viewmodel.UiFileAttribute;
import org.openforis.collect.android.viewmodel.UiNode;
import org.openforis.collect.android.viewmodel.UiNodeChange;
import org.openforis.collect.android.viewmodel.UiRecord;
import org.openforis.collect.android.viewmodel.UiSpatialReferenceSystem;
import org.openforis.collect.android.viewmodel.UiSurvey;
import org.openforis.collect.manager.CodeListManager;
import org.openforis.collect.manager.RecordFileManager;
import org.openforis.collect.manager.RecordManager;
import org.openforis.collect.manager.SpeciesManager;
import org.openforis.collect.manager.SurveyManager;
import org.openforis.collect.manager.exception.SurveyValidationException;
import org.openforis.collect.model.AttributeAddChange;
import org.openforis.collect.model.CollectRecord;
import org.openforis.collect.model.CollectSurvey;
import org.openforis.collect.model.EntityAddChange;
import org.openforis.collect.model.NodeChange;
import org.openforis.collect.model.NodeChangeMap;
import org.openforis.collect.model.NodeChangeSet;
import org.openforis.collect.model.User;
import org.openforis.collect.persistence.SurveyDao;
import org.openforis.collect.persistence.SurveyImportException;
import org.openforis.collect.persistence.jooq.CollectDSLContext;
import org.openforis.idm.metamodel.AttributeDefinition;
import org.openforis.idm.metamodel.CodeAttributeDefinition;
import org.openforis.idm.metamodel.CodeListItem;
import org.openforis.idm.metamodel.FileAttributeDefinition;
import org.openforis.idm.metamodel.ModelVersion;
import org.openforis.idm.metamodel.validation.Check;
import org.openforis.idm.metamodel.validation.DistanceCheck;
import org.openforis.idm.metamodel.validation.ValidationResultFlag;
import org.openforis.idm.metamodel.validation.ValidationResults;
import org.openforis.idm.metamodel.validation.Validator;
import org.openforis.idm.model.Attribute;
import org.openforis.idm.model.CodeAttribute;
import org.openforis.idm.model.Coordinate;
import org.openforis.idm.model.CoordinateAttribute;
import org.openforis.idm.model.Entity;
import org.openforis.idm.model.FileAttribute;
import org.openforis.idm.model.Node;
import org.openforis.idm.model.NodePointer;
import org.openforis.idm.model.Value;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * @author Daniel Wiell
 */
public class CollectModelManager implements DefinitionProvider, CodeListService, CoordinateDestinationService {
    private final SurveyManager surveyManager;
    private final RecordManager recordManager;
    private final CodeListManager codeListManager;
    private final SpeciesManager speciesManager;
    private final RecordFileManager recordFileManager;
    private final CodeListSizeEvaluator codeListSizeEvaluator;

    private final User user = new User();
    private final CollectDSLContext jooqDsl;

    private RecordNodes recordNodes;
    private CollectSurvey selectedSurvey;
    private ModelConverter modelConverter;
    private Definitions definitions;

    public CollectModelManager(SurveyManager surveyManager,
                               RecordManager recordManager,
                               CodeListManager codeListManager,
                               SpeciesManager speciesManager,
                               RecordFileManager recordFileManager,
                               Database database) {
        this.surveyManager = surveyManager;
        this.recordManager = recordManager;
        this.codeListManager = codeListManager;
        this.speciesManager = speciesManager;
        this.recordFileManager = recordFileManager;
        codeListSizeEvaluator = new CodeListSizeEvaluator(new DatabaseCodeListSizeDao(database));

        DefaultConfiguration defaultConfiguration = new DefaultConfiguration();
        defaultConfiguration.setSettings(defaultConfiguration.settings().withRenderSchema(false));
        defaultConfiguration
                .set(database.dataSource())
                .set(SQLDialect.SQLITE);
        jooqDsl = new CollectDSLContext(defaultConfiguration);
    }

    public UiSurvey importSurvey(InputStream inputStream) {
        try {
            CollectSurvey collectSurvey = surveyManager.importModel(inputStream, "survey", false);
            selectSurvey(collectSurvey);
            return modelConverter.toUiSurvey();
        } catch (SurveyImportException e) {
            throw new SurveyException(e);
        } catch (SurveyValidationException e) {
            throw new SurveyException(e);
        }
    }

    public UiSurvey loadSurvey() {
        try {
            codeListSizeEvaluator.reset();
            CollectSurvey collectSurvey = Timer.time(SurveyDao.class, "loadSurvey", new Callable<CollectSurvey>() {
                public CollectSurvey call() throws Exception {
                    SurveyDao surveyDao = surveyManager.getSurveyDao();
                    List<CollectSurvey> surveys = surveyDao.loadAll();
                    if (surveys.isEmpty())
                        return null;
                    return surveys.get(0);
                }
            });
            if (collectSurvey == null)
                return null;
            selectSurvey(collectSurvey);
            return modelConverter.toUiSurvey();
        } catch (Exception e) {
            throw new SurveyException(e);
        }
    }

    public UiRecord addRecord(String entityName, UiSurvey survey) {
        CollectRecord record = recordManager.create(selectedSurvey, entityName, user, latestSurveyVersion(), null, CollectRecord.Step.CLEANSING);
        UiRecord uiRecord = modelConverter.toUiRecord(record, survey);
        recordNodes = new RecordNodes(record);
        return uiRecord;
    }

    public NodeAddedResult<UiEntity> addEntity(final UiEntityCollection uiEntityCollection) {
        Entity parentEntity = recordNodes.getEntityById(uiEntityCollection.getParentEntityId());
        NodeChangeSet changeSet = recordManager.addEntity(parentEntity, uiEntityCollection.getName());
        Entity entity = extractAddedEntity(changeSet);
        UiEntity uiEntity = modelConverter.toUiEntity(selectedSurvey, entity, uiEntityCollection);
        recordNodes.add(entity);
        Map<UiNode, UiNodeChange> nodeChanges = new NodeChangeSetParser(changeSet, uiEntity.getUiRecord()).extractChanges();
        return new NodeAddedResult<UiEntity>(uiEntity, nodeChanges);
    }

    public NodeAddedResult<UiAttribute> addAttribute(UiAttributeCollection uiAttributeCollection) {
        Entity parentEntity = recordNodes.getEntityById(uiAttributeCollection.getParentEntityId());
        UiAttributeDefinition definition = uiAttributeCollection.getDefinition().attributeDefinition;

        Value value = null; // TODO: Set the default value
        NodeChangeSet changeSet = recordManager.addAttribute(parentEntity, definition.name, value, null, null);
        Attribute attribute = extractAddedAttribute(changeSet);
        attribute.setId(IdGenerator.nextId()); // TODO: Not right place to do this - use converter?
        recordNodes.add(attribute);
        UiAttribute uiAttribute = AttributeConverter.toUiAttribute(definition, attribute);
        Map<UiNode, UiNodeChange> nodeChanges = new NodeChangeSetParser(changeSet, uiAttributeCollection.getUiRecord()).extractChanges();
        return new NodeAddedResult<UiAttribute>(uiAttribute, nodeChanges);
    }

    @SuppressWarnings("unchecked")
    public Map<UiNode, UiNodeChange> updateAttribute(final UiAttribute uiAttribute) {
        Attribute attribute = recordNodes.getAttribute(uiAttribute.getId());
        Value value = AttributeConverter.toValue(uiAttribute);
        NodeChangeSet nodeChangeSet = recordManager.updateAttribute(attribute, value);
        Map<UiNode, UiNodeChange> nodeChanges = new NodeChangeSetParser(nodeChangeSet, uiAttribute.getUiRecord()).extractChanges();
        if (uiAttribute instanceof UiCodeAttribute)
            updateChildrenCodeAttributes((UiCodeAttribute) uiAttribute, nodeChanges.keySet());
        return nodeChanges;
    }

    public Map<UiNode, UiNodeChange> validateAttribute(UiAttribute uiAttribute) {
        Attribute attribute = recordNodes.getAttribute(uiAttribute.getId());
        Validator validator = attribute.getRecord().getSurveyContext().getValidator();
        ValidationResults attributeResult = validator.validate(attribute);
        ValidationResultFlag cardinalityResult = validator.validateMinCount(attribute.getParent(), attribute.getName());
        NodeChangeMap changeMap = new NodeChangeMap();
        changeMap.addMinCountValidationResultChange(new NodePointer(attribute), cardinalityResult);
        changeMap.addValidationResultChange(attribute, attributeResult);
        return new NodeChangeSetParser(changeMap, uiAttribute.getUiRecord()).extractChanges();
    }

    /**
     * Update children attribute codes with correct label.
     */
    private void updateChildrenCodeAttributes(UiCodeAttribute uiCodeAttribute, Collection<UiNode> uiNodes) {
        int parentDefinitionId = Integer.parseInt(uiCodeAttribute.getDefinition().id);
        for (UiNode uiNode : uiNodes) {
            if (uiNode instanceof UiCodeAttribute) {
                CodeAttributeDefinition nodeDefinition = (CodeAttributeDefinition) selectedSurvey.getSchema().getDefinitionById(Integer.parseInt(uiNode.getDefinition().id));
                CodeAttributeDefinition parentDefinition = nodeDefinition.getParentCodeAttributeDefinition();
                if (parentDefinition != null && parentDefinition.getId() == parentDefinitionId) {
                    CodeAttribute childCodeAttribute = recordNodes.getCodeAttribute(uiNode.getId());
                    CodeListItem item = codeListManager.loadItemByAttribute(childCodeAttribute);
                    if (item != null) {
                        UiCodeAttribute childUiCodeAttribute = (UiCodeAttribute) uiNode;
                        UiCode updatedCode = new UiCode(item.getCode(), item.getLabel(), null, childUiCodeAttribute.getDefinition().isValueShown());
                        childUiCodeAttribute.setCode(updatedCode);
                    }
                }
            }
        }
    }

    public Map<UiNode, UiNodeChange> removeAttribute(UiAttribute uiAttribute) {
        Attribute attribute = recordNodes.getAttribute(uiAttribute.getId());
        NodeChangeSet nodeChangeSet = recordManager.deleteNode(attribute);
        recordNodes.remove(uiAttribute.getId());
        return new NodeChangeSetParser(nodeChangeSet, uiAttribute.getUiRecord()).extractChanges();
    }

    public Map<UiNode, UiNodeChange> removeEntity(UiEntity uiEntity) {
        Node node = recordNodes.getEntityById(uiEntity.getId());
        NodeChangeSet nodeChangeSet = recordManager.deleteNode(node);
        recordNodes.remove(uiEntity.getId());
        return new NodeChangeSetParser(nodeChangeSet, uiEntity.getUiRecord()).extractChanges();
    }

    public void recordSelected(UiRecord uiRecord) {
        recordSelected(uiRecord, true);
    }

    public void recordSelected(UiRecord uiRecord, boolean enableDependencyGraphs) {
        CollectRecord record = modelConverter.toCollectRecord(uiRecord, selectedSurvey, enableDependencyGraphs);
        recordNodes = new RecordNodes(record);
    }

    public Definition getById(String definitionId) {
        Definition definition = definitions.definitionById(definitionId);
        if (definition == null)
            throw new IllegalArgumentException("No definition exists with id " + definitionId);
        return definition;
    }

    public UiCodeList codeList(UiCodeAttribute uiAttribute) {
        CodeAttribute attribute = recordNodes.getCodeAttribute(uiAttribute.getId());
        List<CodeListItem> items = codeListManager.loadValidItems(attribute.getParent(), attribute.getDefinition());
        boolean valueShown = selectedSurvey.getUIOptions().getShowCode(attribute.getDefinition());
        return modelConverter.toUiCodeList(items, valueShown);
    }

    public UiCodeList codeList(UiAttributeCollection uiAttributeCollection) {
        if (!uiAttributeCollection.getDefinition().isOfAttributeType(UiCodeAttribute.class))
            throw new IllegalStateException("uiAttributeCollection " + uiAttributeCollection + " expected to have UiAttributeCollection attribute type");
        Entity parentEntity = recordNodes.getEntityById(uiAttributeCollection.getParentEntityId());
        Definition definition = uiAttributeCollection.getDefinition().attributeDefinition;
        CodeAttributeDefinition codeAttributeDefinition = (CodeAttributeDefinition) selectedSurvey.getSchema().getDefinitionById(Integer.parseInt(definition.id));
        List<CodeListItem> items = codeListManager.loadValidItems(parentEntity, codeAttributeDefinition);
        boolean valueShown = selectedSurvey.getUIOptions().getShowCode(codeAttributeDefinition);
        return modelConverter.toUiCodeList(items, valueShown);
    }

    public boolean isParentCodeAttribute(UiAttribute attribute, UiCodeAttribute codeAttribute) {
        if (!(attribute instanceof UiCodeAttribute))
            return false;
        CodeAttributeDefinition definition = (CodeAttributeDefinition) getDefinition(codeAttribute);
        CodeAttributeDefinition parentDefinition = definition.getParentCodeAttributeDefinition();
        return parentDefinition != null && parentDefinition.getId() == getDefinition(attribute).getId();
    }

    public boolean isParentCodeAttribute(UiAttribute attribute, UiAttributeCollection attributeCollection) {
        if (!(attribute instanceof UiCodeAttribute) || !UiCodeAttribute.class.isAssignableFrom(attributeCollection.getDefinition().attributeType))
            return false;
        CodeAttributeDefinition definition = (CodeAttributeDefinition) getDefinition(attributeCollection);
        CodeAttributeDefinition parentDefinition = definition.getParentCodeAttributeDefinition();
        return parentDefinition != null && parentDefinition.getId() == getDefinition(attribute).getId();
    }

    public int getMaxCodeListSize(UiCodeAttribute uiAttribute) {
        CodeAttribute attribute = recordNodes.getCodeAttribute(uiAttribute.getId());
        return codeListSizeEvaluator.size(attribute.getDefinition());
    }


    public int getMaxCodeListSize(Definition definition) {
        return codeListSizeEvaluator.size((CodeAttributeDefinition) selectedSurvey.getSchema().getDefinitionById(Integer.parseInt(definition.id)));
    }

    private AttributeDefinition getDefinition(UiAttribute uiAttribute) {
        int definitionId = Integer.parseInt(uiAttribute.getDefinition().id);
        return (AttributeDefinition) selectedSurvey.getSchema().getDefinitionById(definitionId);
    }

    private AttributeDefinition getDefinition(UiAttributeCollection attributeCollection) {
        int definitionId = Definitions.extractOriginalDefinitionId(attributeCollection.getDefinition());
        return (AttributeDefinition) selectedSurvey.getSchema().getDefinitionById(definitionId);
    }

    private void selectSurvey(CollectSurvey survey) {
        selectedSurvey = survey;
        definitions = new Definitions(selectedSurvey);
        modelConverter = new ModelConverter(selectedSurvey, definitions);
    }

    private Entity extractAddedEntity(NodeChangeSet changeSet) {
        for (NodeChange<?> nodeChange : changeSet.getChanges()) {
            if (nodeChange instanceof EntityAddChange)
                return ((EntityAddChange) nodeChange).getNode();
        }
        throw new IllegalStateException("No " + EntityAddChange.class.getName() + " found in change set");
    }

    private Attribute extractAddedAttribute(NodeChangeSet changeSet) {
        for (NodeChange<?> nodeChange : changeSet.getChanges()) {
            if (nodeChange instanceof AttributeAddChange)
                return ((AttributeAddChange) nodeChange).getNode();
        }
        throw new IllegalStateException("No " + EntityAddChange.class.getName() + " found in change set");
    }

    private String latestSurveyVersion() {
        List<ModelVersion> versions = selectedSurvey.getVersions();
        if (versions == null || versions.isEmpty())
            return null;
        return versions.get(versions.size() - 1).getName();
    }

    public void exportSurvey(UiSurvey uiSurvey, File exportFile, boolean excludeBinaries, final ExportListener exportListener) throws IOException {
        new SurveyExporter(uiSurvey, selectedSurvey, surveyManager, excludeBinaries, new SurveyExporter.CollectRecordProvider() {
            public CollectRecord record(int recordId) {
                exportListener.beforeRecordExport(recordId);
                return getCollectRecordForExporting(recordId);
            }
        }, recordFileManager).export(exportFile);
    }

    private CollectRecord getCollectRecordForExporting(int recordId) {
        Entity rootEntity = recordNodes.getEntityById(recordId);
        CollectRecord collectRecord = new CollectRecord(selectedSurvey, latestSurveyVersion(), rootEntity.getName(), false);
        collectRecord.setId(recordId);
        collectRecord.replaceRootEntity(rootEntity);
        return collectRecord;
    }

    public File file(UiFileAttribute uiFileAttribute) {
        FileAttribute attribute = (FileAttribute) recordNodes.getAttribute(uiFileAttribute.getId());
        FileAttributeDefinition def = attribute.getDefinition();
        File dir = new File(recordFileManager.getDefaultStorageDirectory().getPath() + "/" + RecordFileManager.getRepositoryRelativePath(def));
        String fileName = org.apache.commons.lang3.StringUtils.isEmpty(attribute.getFilename())
                ? String.format("%s.%s", UUID.randomUUID(), "jpg")
                : attribute.getFilename(); // For backwards compatibility - previously internal ids was incorrectly used in filename
        return new File(dir, fileName);
    }


    public double[] destination(UiCoordinateAttribute uiAttribute, double[] coordinate) {
        CoordinateAttribute attribute = (CoordinateAttribute) recordNodes.getAttribute(uiAttribute.getId());
        Coordinate previousValue = attribute.getValue();
        try {
            attribute.setValue(new Coordinate(coordinate[0], coordinate[1], uiAttribute.getSpatialReferenceSystem().id));
            for (Check<?> check : attribute.getDefinition().getChecks()) {
                if (check instanceof DistanceCheck) {
                    Coordinate destinationPoint = ((DistanceCheck) check).evaluateDestinationPoint(attribute);
                    if (destinationPoint == null)
                        return null;
                    return CoordinateUtils.transform(
                            uiAttribute.getSpatialReferenceSystem(),
                            new double[]{destinationPoint.getX(), destinationPoint.getY()},
                            UiSpatialReferenceSystem.LAT_LNG_SRS
                    );

                }
            }
            throw new IllegalStateException("No distance check for " + uiAttribute);
        } finally {
            attribute.setValue(previousValue);
        }
    }

    public ValidationResultFlag validateDistance(UiCoordinateAttribute uiAttribute, double[] coordinate) {
        CoordinateAttribute attribute = (CoordinateAttribute) recordNodes.getAttribute(uiAttribute.getId());
        Coordinate previousValue = attribute.getValue();
        try {
            attribute.setValue(new Coordinate(coordinate[0], coordinate[1], uiAttribute.getSpatialReferenceSystem().id));
            ValidationResultFlag worstFlag = ValidationResultFlag.OK;
            for (Check<?> check : attribute.getDefinition().getChecks()) {
                if (check instanceof DistanceCheck) {
                    ValidationResultFlag flag = ((DistanceCheck) check).evaluate(attribute);
                    if (flag == ValidationResultFlag.ERROR)
                        return flag;
                    if (flag == ValidationResultFlag.WARNING)
                        worstFlag = flag;
                }
            }
            return worstFlag;
        } finally {
            attribute.setValue(previousValue);
        }

    }

    public CollectSurvey getSelectedSurvey() {
        return selectedSurvey;
    }

    public interface ExportListener {
        void beforeRecordExport(int recordId);
    }

}