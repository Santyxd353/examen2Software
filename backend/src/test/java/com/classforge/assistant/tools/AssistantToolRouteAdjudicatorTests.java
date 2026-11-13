package com.classforge.assistant.tools;

import com.classforge.assistant.AssistantEntityReferenceResolver;
import com.classforge.assistant.AssistantIntentHintResolver;
import com.classforge.project.domain.document.DiagramLayout;
import com.classforge.project.domain.document.DiagramNodeLayout;
import com.classforge.project.domain.document.Multiplicity;
import com.classforge.project.domain.document.ProjectDocument;
import com.classforge.project.domain.document.UmlAttribute;
import com.classforge.project.domain.document.UmlClass;
import com.classforge.project.domain.document.UmlDataType;
import com.classforge.project.domain.document.UmlModel;
import com.classforge.project.domain.document.UmlRelationship;
import com.classforge.project.domain.document.UmlRelationshipType;
import com.classforge.project.domain.document.UmlVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantToolRouteAdjudicatorTests {

    private final AssistantEntityReferenceResolver entityResolver = new AssistantEntityReferenceResolver();
    private final AssistantIntentHintResolver intentResolver = new AssistantIntentHintResolver();
    private final AssistantToolRouteAdjudicator adjudicator = new AssistantToolRouteAdjudicator(
            intentResolver,
            entityResolver,
            new AssistantCompoundRequestDetector()
    );
    private final ProjectDocument document = fixture();

    @Test
    void simpleHoldoutPhrasesCollapseToOneProjectAwareStep() {
        assertRoute(
                "La clase Veternario debe llamarse DoctorVeterinario",
                AssistantToolName.RENAME_CLASS,
                List.of(AssistantToolName.CREATE_CLASS, AssistantToolName.RENAME_CLASS)
        );
        assertRoute(
                "Ponle DoctorVeterinario de nombre a Veterinairo",
                AssistantToolName.RENAME_CLASS,
                List.of(AssistantToolName.ADD_ATTRIBUTES, AssistantToolName.RENAME_CLASS)
        );
        assertRoute(
                "Pon un celular a la clase Propietario",
                AssistantToolName.ADD_ATTRIBUTES,
                List.of(AssistantToolName.CREATE_CLASS, AssistantToolName.ADD_ATTRIBUTES)
        );
        assertRoute(
                "Renombra pseo de Mascota a masaKg",
                AssistantToolName.RENAME_ATTRIBUTE,
                List.of(AssistantToolName.RENAME_CLASS, AssistantToolName.RENAME_ATTRIBUTE)
        );
        assertRoute(
                "Consulta puede quedarse sin motivo, quitalo",
                AssistantToolName.DELETE_ATTRIBUTE,
                List.of(AssistantToolName.DELETE_RELATIONSHIP, AssistantToolName.DELETE_ATTRIBUTE)
        );
    }


    @Test
    void naturalNewClassPhraseDoesNotBecomeCompoundAttributeAddition() {
        AssistantCompoundRequestDetector detector = new AssistantCompoundRequestDetector();
        String prompt = "Añade al modelo una nueva clase que se llame TurnoEmergencia";

        assertEquals(false, detector.isCompound(prompt));
        assertEquals(
                List.of(AssistantToolName.CREATE_CLASS),
                adjudicator.adjudicate(
                        prompt,
                        document,
                        List.of(AssistantToolName.CREATE_CLASS, AssistantToolName.ADD_ATTRIBUTES)
                )
        );
    }

    @Test
    void relationshipHoldoutPhrasesUseGroundedSemanticFamily() {
        assertRoute(
                "Asocia Propetario con Veterinaria",
                AssistantToolName.CREATE_ASSOCIATION,
                List.of(AssistantToolName.CREATE_CLASS, AssistantToolName.CREATE_ASSOCIATION)
        );
        assertRoute(
                "Veterinaria contiene Factrua como composicion",
                AssistantToolName.CREATE_COMPOSITION,
                List.of(AssistantToolName.CREATE_CLASS, AssistantToolName.CREATE_COMPOSITION)
        );
        assertRoute(
                "En Propietario Mascota permite de cero a muchas Mascotas",
                AssistantToolName.SET_RELATIONSHIP_MULTIPLICITY,
                List.of(AssistantToolName.CREATE_ASSOCIATION, AssistantToolName.SET_RELATIONSHIP_MULTIPLICITY)
        );
        assertRoute(
                "Un propietario puede tener ninguna o varias mascotas",
                AssistantToolName.SET_RELATIONSHIP_MULTIPLICITY,
                List.of(AssistantToolName.CREATE_ASSOCIATION, AssistantToolName.SET_RELATIONSHIP_MULTIPLICITY)
        );
    }

    @Test
    void compoundRequestUsesDependencyOrderedCanonicalRoute() {
        List<AssistantToolName> route = adjudicator.adjudicate(
                "Crea Cliente, agregale email STRING y relaciona Cliente con Factura",
                document,
                List.of(
                        AssistantToolName.CREATE_ASSOCIATION,
                        AssistantToolName.CREATE_CLASS,
                        AssistantToolName.ADD_ATTRIBUTES,
                        AssistantToolName.CREATE_CLASS
                )
        );

        assertEquals(
                List.of(
                        AssistantToolName.CREATE_CLASS,
                        AssistantToolName.ADD_ATTRIBUTES,
                        AssistantToolName.CREATE_ASSOCIATION
                ),
                route
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Cree a una clase factura con un atributo id de tipo Long",
            "Crea una clase Factura con un atributo id de tipo Long"
    })
    void explicitAttributeOnNewClassKeepsBothStepsForVoiceAndText(String prompt) {
        List<AssistantToolName> route = List.of(AssistantToolName.CREATE_CLASS, AssistantToolName.ADD_ATTRIBUTES);

        assertEquals(route, adjudicator.adjudicate(prompt, ProjectDocument.empty(), route));
    }

    @Test
    void simpleUnknownReferenceKeepsLlmRouteSoResolverCanFailClosed() {
        List<AssistantToolName> route = adjudicator.adjudicate(
                "Desconecta Veterinaria de EntidadFantasma",
                document,
                List.of(AssistantToolName.DELETE_CLASS, AssistantToolName.DELETE_RELATIONSHIP)
        );

        assertEquals(List.of(AssistantToolName.DELETE_RELATIONSHIP), route);
    }

    private void assertRoute(
            String prompt,
            AssistantToolName expected,
            List<AssistantToolName> proposed
    ) {
        assertEquals(List.of(expected), adjudicator.adjudicate(prompt, document, proposed));
    }

    private ProjectDocument fixture() {
        UUID animal = UUID.randomUUID();
        UUID animalDomestico = UUID.randomUUID();
        UUID mascota = UUID.randomUUID();
        UUID propietario = UUID.randomUUID();
        UUID veterinaria = UUID.randomUUID();
        UUID veterinario = UUID.randomUUID();
        UUID consulta = UUID.randomUUID();
        UUID vacuna = UUID.randomUUID();
        UUID factura = UUID.randomUUID();

        UmlClass animalClass = new UmlClass(animal, "Animal", List.of());
        UmlClass animalDomesticoClass = new UmlClass(animalDomestico, "AnimalDomestico", List.of());
        UmlClass mascotaClass = new UmlClass(
                mascota,
                "Mascota",
                List.of(new UmlAttribute(
                        UUID.randomUUID(), "peso", UmlDataType.DECIMAL, null,
                        UmlVisibility.PRIVATE, true, false
                ))
        );
        UmlClass propietarioClass = new UmlClass(propietario, "Propietario", List.of());
        UmlClass veterinariaClass = new UmlClass(veterinaria, "Veterinaria", List.of());
        UmlClass veterinarioClass = new UmlClass(veterinario, "Veterinario", List.of());
        UmlClass consultaClass = new UmlClass(
                consulta,
                "Consulta",
                List.of(new UmlAttribute(
                        UUID.randomUUID(), "motivo", UmlDataType.STRING, null,
                        UmlVisibility.PRIVATE, true, false
                ))
        );
        UmlClass vacunaClass = new UmlClass(vacuna, "Vacuna", List.of());
        UmlClass facturaClass = new UmlClass(factura, "Factura", List.of());

        List<UmlClass> classes = List.of(
                animalClass,
                animalDomesticoClass,
                mascotaClass,
                propietarioClass,
                veterinariaClass,
                veterinarioClass,
                consultaClass,
                vacunaClass,
                facturaClass
        );

        List<UmlRelationship> relationships = List.of(
                new UmlRelationship(
                        UUID.randomUUID(), propietario, mascota,
                        UmlRelationshipType.ASSOCIATION, Multiplicity.one(), Multiplicity.one()
                ),
                new UmlRelationship(
                        UUID.randomUUID(), mascota, vacuna,
                        UmlRelationshipType.ASSOCIATION, Multiplicity.many(), Multiplicity.many()
                )
        );

        Map<UUID, DiagramNodeLayout> nodes = new LinkedHashMap<>();
        for (int index = 0; index < classes.size(); index++) {
            nodes.put(classes.get(index).id(), DiagramNodeLayout.defaultForIndex(index));
        }

        return new ProjectDocument(
                ProjectDocument.CURRENT_SCHEMA_VERSION,
                new UmlModel(classes, relationships),
                new DiagramLayout(nodes)
        );
    }
}
