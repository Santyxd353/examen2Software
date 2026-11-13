package com.classforge.assistant.vision;

import com.classforge.assistant.AssistantPlanningException;
import com.classforge.assistant.codex.CodexApiException;
import com.classforge.assistant.groq.GroqApiException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class VisionHybridAnalyzer implements VisionModelGateway {

    private final VisionModelGateway semanticGateway;
    private final VisionHybridModelGateway hybridGateway;
    private final UmlClassRegionDetector classRegionDetector;
    private final VisionGeometryClassMappingValidator mappingValidator;
    private final VisionHybridImageFactory imageFactory;
    private final UmlDiagramGeometryAnalyzer geometryAnalyzer;
    private final VisionRelationshipEvidenceSheetRenderer sheetRenderer;
    private final VisionHybridProposalAssembler assembler;
    private final VisionMultiplicityParser multiplicityParser;
    private final boolean enabled;
    private final int minClasses;
    private final boolean fallbackToSemantic;
    private final boolean geometryOnly;
    private final boolean collectDiagnostics;
    private volatile VisionHybridDiagnostics lastDiagnostics;

    public VisionHybridAnalyzer(
            VisionModelGateway semanticGateway,
            VisionHybridModelGateway hybridGateway,
            UmlClassRegionDetector classRegionDetector,
            VisionGeometryClassMappingValidator mappingValidator,
            VisionHybridImageFactory imageFactory,
            UmlDiagramGeometryAnalyzer geometryAnalyzer,
            VisionRelationshipEvidenceSheetRenderer sheetRenderer,
            VisionHybridProposalAssembler assembler,
            boolean enabled,
            int minClasses,
            boolean fallbackToSemantic
    ) {
        this(
                semanticGateway, hybridGateway, classRegionDetector, mappingValidator, imageFactory,
                geometryAnalyzer, sheetRenderer, assembler, enabled, minClasses, fallbackToSemantic, false, false
        );
    }

    public VisionHybridAnalyzer(
            VisionModelGateway semanticGateway,
            VisionHybridModelGateway hybridGateway,
            UmlClassRegionDetector classRegionDetector,
            VisionGeometryClassMappingValidator mappingValidator,
            VisionHybridImageFactory imageFactory,
            UmlDiagramGeometryAnalyzer geometryAnalyzer,
            VisionRelationshipEvidenceSheetRenderer sheetRenderer,
            VisionHybridProposalAssembler assembler,
            boolean enabled,
            int minClasses,
            boolean fallbackToSemantic,
            boolean geometryOnly
    ) {
        this(
                semanticGateway, hybridGateway, classRegionDetector, mappingValidator, imageFactory,
                geometryAnalyzer, sheetRenderer, assembler, enabled, minClasses, fallbackToSemantic, geometryOnly, true
        );
    }

    public VisionHybridAnalyzer(
            VisionModelGateway semanticGateway, VisionHybridModelGateway hybridGateway,
            UmlClassRegionDetector classRegionDetector, VisionGeometryClassMappingValidator mappingValidator,
            VisionHybridImageFactory imageFactory, UmlDiagramGeometryAnalyzer geometryAnalyzer,
            VisionRelationshipEvidenceSheetRenderer sheetRenderer, VisionHybridProposalAssembler assembler,
            boolean enabled, int minClasses, boolean fallbackToSemantic, boolean geometryOnly, boolean collectDiagnostics
    ) {
        this.semanticGateway = semanticGateway;
        this.hybridGateway = hybridGateway;
        this.classRegionDetector = classRegionDetector;
        this.mappingValidator = mappingValidator;
        this.imageFactory = imageFactory;
        this.geometryAnalyzer = geometryAnalyzer;
        this.sheetRenderer = sheetRenderer;
        this.assembler = assembler;
        this.multiplicityParser = new VisionMultiplicityParser();
        this.enabled = enabled;
        this.minClasses = Math.max(2, minClasses);
        this.fallbackToSemantic = fallbackToSemantic;
        this.geometryOnly = geometryOnly;
        this.collectDiagnostics = collectDiagnostics;
    }

    @Override
    public VisionUmlProposal analyze(VisionNormalizedImage image, VisionProjectContext context) {
        lastDiagnostics = null;
        VisionUmlProposal semantic = semanticGateway.analyze(image, context);
        if (!enabled || semantic == null || semantic.safeClasses().size() < minClasses) {
            return semantic;
        }
        try {
            UmlClassRegionDetection detection = classRegionDetector.detect(
                    image,
                    semantic.safeClasses().size()
            );
            updateDiagnostics(detection, null, null, null, null, List.of(), List.of(), List.of());
            VisionNormalizedImage labeledRegions = imageFactory.png(
                    "class-regions.png",
                    detection.overlayPng(),
                    image.width(),
                    image.height()
            );
            VisionGeometryClassMappingProposal mapping = hybridGateway.mapClassRegions(
                    labeledRegions,
                    detection.safeRegions(),
                    semantic.safeClasses()
            );
            updateDiagnostics(detection, mapping, null, null, null, List.of(), List.of(), List.of());
            List<VisionGeometryClassRegion> mappedRegions = mappingValidator.validateAndBind(
                    mapping,
                    detection.safeRegions(),
                    semantic.safeClasses()
            );
            UmlDiagramGeometry geometry = geometryAnalyzer.analyze(image, mappedRegions);
            updateDiagnostics(detection, mapping, geometry, null, null, List.of(), List.of(), List.of());

            if (geometryOnly) {
                List<String> warnings = new ArrayList<>(semantic.safeWarnings());
                warnings.add("HYBRID_GEOMETRY_ONLY: CV-first class boxes/mapping/geometry ejecutados sin anotacion VLM de relaciones.");
                return new VisionUmlProposal(
                        semantic.summary(), semantic.safeClasses(), semantic.safeRelationships(),
                        List.copyOf(new LinkedHashSet<>(warnings)), semantic.confidence()
                );
            }

            if (geometry.safeEdgeCandidates().isEmpty()) {
                throw new AssistantPlanningException("OpenCV no genero pares candidatos para el diagrama denso.");
            }
            VisionNormalizedImage sheet = collectDiagnostics ? sheetRenderer.render(image, geometry) : null;
            updateDiagnostics(detection, mapping, geometry, sheet, null, List.of(), List.of(), List.of());
            EdgeAnnotations annotations = annotateEdges(image, geometry, semantic.safeClasses(), collectDiagnostics);
            updateDiagnostics(
                    detection, mapping, geometry, sheet, annotations.annotation(), annotations.relationshipPanels(),
                    annotations.multiplicityPanels(), annotations.observations()
            );
            return assembler.assemble(semantic, geometry, annotations.annotation());
        } catch (GroqApiException | CodexApiException exception) {
            // Provider failures must remain actionable, even with semantic fallback enabled.
            throw exception;
        } catch (RuntimeException exception) {
            if (!fallbackToSemantic) {
                if (exception instanceof VisionModelGatewayException gatewayException) {
                    throw gatewayException;
                }
                throw new VisionModelGatewayException(
                        VisionModelGatewayException.Reason.OUTPUT_CONTRACT,
                        "El pipeline hibrido rechazo la evidencia: " + compact(exception.getMessage()),
                        exception
                );
            }
            List<String> warnings = new ArrayList<>(semantic.safeWarnings());
            warnings.add("HYBRID_CV_FALLBACK: " + compact(exception.getMessage()));
            return new VisionUmlProposal(
                    semantic.summary(),
                    semantic.safeClasses(),
                    semantic.safeRelationships(),
                    List.copyOf(new LinkedHashSet<>(warnings)),
                    semantic.confidence()
            );
        }
    }

    public VisionHybridDiagnostics lastDiagnostics() {
        return lastDiagnostics;
    }

    private EdgeAnnotations annotateEdges(
            VisionNormalizedImage image,
            UmlDiagramGeometry geometry,
            List<VisionClassProposal> classes, boolean retainPanels
    ) {
        List<VisionHybridEdgeAnnotation> edges = new ArrayList<>();
        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        List<VisionNormalizedImage> relationshipPanels = new ArrayList<>();
        List<VisionNormalizedImage> multiplicityPanels = new ArrayList<>();
        List<VisionHybridMultiplicityObservationDiagnostic> observations = new ArrayList<>();
        Double confidence = null;
        for (VisionGeometryEdgeCandidate candidate : geometry.safeEdgeCandidates()) {
            VisionNormalizedImage relationshipPanel = sheetRenderer.renderEdge(image, candidate);
            if (retainPanels) relationshipPanels.add(relationshipPanel);
            VisionHybridRelationshipClassificationProposal classification = hybridGateway.classifyRelationship(
                    relationshipPanel, candidate, classes
            );
            if (classification == null || classification.safeEdges().size() != 1) {
                throw new VisionModelGatewayException(
                        VisionModelGatewayException.Reason.OUTPUT_CONTRACT,
                        "La clasificacion por edge debe contener exactamente un resultado.", null
                );
            }
            if (!candidate.edgeId().equals(classification.safeEdges().getFirst().edgeId())) {
                throw new VisionModelGatewayException(
                        VisionModelGatewayException.Reason.OUTPUT_CONTRACT,
                        "La anotacion por edge devolvio un edgeId distinto del solicitado.", null
                );
            }
            VisionClassProposal aClass = endpointClass(classes, candidate.aClassRef());
            VisionClassProposal bClass = endpointClass(classes, candidate.bClassRef());
            EndpointMultiplicityResult multiplicityA = observeMultiplicity(
                    image, geometry.safeClassRegions(), geometry.safeEdgeCandidates(), candidate,
                    VisionHybridEndpoint.A, aClass, multiplicityPanels, retainPanels
            );
            observations.add(multiplicityA.diagnostic());
            EndpointMultiplicityResult multiplicityB = observeMultiplicity(
                    image, geometry.safeClassRegions(), geometry.safeEdgeCandidates(), candidate,
                    VisionHybridEndpoint.B, bClass, multiplicityPanels, retainPanels
            );
            observations.add(multiplicityB.diagnostic());
            VisionHybridEdgeClassification edge = classification.safeEdges().getFirst();
            edges.add(new VisionHybridEdgeAnnotation(
                    edge.edgeId(), edge.type(), edge.markerAt(), multiplicityA.multiplicity(), multiplicityB.multiplicity(),
                    edge.evidenceLabel(), edge.confidence()
            ));
            warnings.addAll(classification.safeWarnings());
            if (classification.confidence() != null) {
                confidence = confidence == null ? classification.confidence()
                        : Math.min(confidence, classification.confidence());
            }
        }
        return new EdgeAnnotations(
                new VisionHybridRelationshipAnnotationProposal(List.copyOf(edges), List.copyOf(warnings), confidence),
                List.copyOf(relationshipPanels), List.copyOf(multiplicityPanels), List.copyOf(observations)
        );
    }

    private EndpointMultiplicityResult observeMultiplicity(
            VisionNormalizedImage image,
            List<VisionGeometryClassRegion> classRegions,
            List<VisionGeometryEdgeCandidate> candidates,
            VisionGeometryEdgeCandidate candidate,
            VisionHybridEndpoint endpoint,
            VisionClassProposal endpointClass,
            List<VisionNormalizedImage> panels, boolean retainPanels
    ) {
        VisionNormalizedImage transcriptionPanel = sheetRenderer.renderMultiplicityTranscriptionConditioned(
                image, candidate, endpoint, endpointClass, candidates
        );
        if (retainPanels) {
            panels.add(transcriptionPanel);
            panels.addAll(sheetRenderer.renderMultiplicityTranscriptionMaskDiagnostics(
                    image, candidate, endpoint, endpointClass, candidates
            ));
        }
        VisionHybridMultiplicityTranscription transcription = hybridGateway.transcribeMultiplicity(
                transcriptionPanel, candidate, endpoint, endpointClass
        );
        if (transcription == null || !candidate.edgeId().equals(transcription.edgeId()) || endpoint != transcription.endpoint()
                || (transcription.rawLabel() != null
                && (transcription.rawLabel().isBlank() || transcription.rawLabel().length() > 16))
                || !validConfidence(transcription == null ? null : transcription.confidence())) {
            throw new VisionModelGatewayException(
                    VisionModelGatewayException.Reason.OUTPUT_CONTRACT,
                    "La transcripcion de multiplicidad no corresponde al endpoint solicitado.", null
            );
        }
        if (transcription.rawLabel() == null) {
            return new EndpointMultiplicityResult(
                    null, new VisionHybridMultiplicityObservationDiagnostic(
                            candidate.edgeId(), endpoint, endpointClass.ref(), null, transcription.confidence(),
                            null, null, List.of(), null, null, null, false
                    )
            );
        }
        VisionGeometryClassRegion endpointClassRegion = endpointClassRegion(classRegions, endpointClass.ref());
        List<String> visibleCompetingEdgeIds = sheetRenderer.ownershipRenderingMetadata(
                image, candidate, endpoint, endpointClass.ref(), endpointClassRegion, candidates
        ).competitors().stream().map(VisionRelationshipEvidenceSheetRenderer.OwnershipEndpointOverlay::edgeId).toList();
        VisionNormalizedImage attributionPanel = sheetRenderer.renderMultiplicityAttribution(
                image, candidate, endpoint, endpointClass, endpointClassRegion, candidates, transcription.rawLabel()
        );
        if (retainPanels) panels.add(attributionPanel);
        VisionHybridMultiplicityAttribution attribution = hybridGateway.attributeMultiplicity(
                attributionPanel, candidate, endpoint, endpointClass, transcription.rawLabel(), visibleCompetingEdgeIds
        );
        if (attribution == null || !candidate.edgeId().equals(attribution.edgeId()) || endpoint != attribution.endpoint()
                || !allowedOwners(candidate.edgeId(), visibleCompetingEdgeIds).contains(attribution.owner())
                || !validConfidence(attribution.confidence())) {
            throw new VisionModelGatewayException(
                    VisionModelGatewayException.Reason.OUTPUT_CONTRACT,
                    "La verificacion de ownership no corresponde al endpoint solicitado.", null
            );
        }
        VisionMultiplicityProposal multiplicity = candidate.edgeId().equals(attribution.owner())
                ? multiplicityParser.parse(transcription.rawLabel()) : null;
        String rejectionReason = multiplicity != null ? null
                : visibleCompetingEdgeIds.contains(attribution.owner()) ? "OWNED_BY_COMPETING_EDGE" : attribution.owner();
        return new EndpointMultiplicityResult(
                multiplicity,
                new VisionHybridMultiplicityObservationDiagnostic(
                        candidate.edgeId(), endpoint, endpointClass.ref(), transcription.rawLabel(), transcription.confidence(),
                        attribution.owner(), attribution.confidence(), visibleCompetingEdgeIds, attribution.owner(), rejectionReason,
                        multiplicity, multiplicity != null
                )
        );
    }

    private VisionClassProposal endpointClass(List<VisionClassProposal> classes, String ref) {
        return classes.stream().filter(umlClass -> ref.equals(umlClass.ref())).findFirst().orElseThrow(
                () -> new AssistantPlanningException("El endpoint hibrido no apunta a una clase confirmada.")
        );
    }

    private VisionGeometryClassRegion endpointClassRegion(List<VisionGeometryClassRegion> regions, String classRef) {
        List<VisionGeometryClassRegion> matches = regions.stream()
                .filter(region -> classRef.equals(region.classRef()))
                .toList();
        if (matches.size() != 1) {
            throw new VisionModelGatewayException(
                    VisionModelGatewayException.Reason.OUTPUT_CONTRACT,
                    "La evidencia de ownership requiere exactamente una region geometrica para la clase del endpoint.", null
            );
        }
        return matches.getFirst();
    }

    private boolean validConfidence(Double confidence) {
        return confidence == null || (confidence >= 0.0 && confidence <= 1.0);
    }

    private Set<String> allowedOwners(String currentEdgeId, List<String> visibleCompetingEdgeIds) {
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        owners.add(currentEdgeId);
        owners.addAll(visibleCompetingEdgeIds);
        owners.add("AMBIGUOUS");
        owners.add("NONE");
        return owners;
    }


    private VisionHybridDiagnostics diagnostics(
            UmlClassRegionDetection detection,
            VisionGeometryClassMappingProposal mapping,
            UmlDiagramGeometry geometry,
            VisionNormalizedImage sheet,
            VisionHybridRelationshipAnnotationProposal annotation,
            List<VisionNormalizedImage> relationshipPanels,
            List<VisionNormalizedImage> multiplicityPanels,
            List<VisionHybridMultiplicityObservationDiagnostic> observations
    ) {
        return new VisionHybridDiagnostics(
                detection, mapping, geometry, sheet, annotation, relationshipPanels, multiplicityPanels, observations
        );
    }

    private void updateDiagnostics(
            UmlClassRegionDetection detection, VisionGeometryClassMappingProposal mapping, UmlDiagramGeometry geometry,
            VisionNormalizedImage sheet, VisionHybridRelationshipAnnotationProposal annotation,
            List<VisionNormalizedImage> relationshipPanels, List<VisionNormalizedImage> multiplicityPanels,
            List<VisionHybridMultiplicityObservationDiagnostic> observations
    ) {
        if (collectDiagnostics) {
            lastDiagnostics = diagnostics(detection, mapping, geometry, sheet, annotation, relationshipPanels, multiplicityPanels, observations);
        }
    }

    private record EdgeAnnotations(
            VisionHybridRelationshipAnnotationProposal annotation,
            List<VisionNormalizedImage> relationshipPanels,
            List<VisionNormalizedImage> multiplicityPanels,
            List<VisionHybridMultiplicityObservationDiagnostic> observations
    ) {
    }

    private record EndpointMultiplicityResult(
            VisionMultiplicityProposal multiplicity,
            VisionHybridMultiplicityObservationDiagnostic diagnostic
    ) {
    }

    private String compact(String message) {
        if (message == null || message.isBlank()) {
            return "fallo hibrido sin diagnostico";
        }
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 240) + "...";
    }
}
