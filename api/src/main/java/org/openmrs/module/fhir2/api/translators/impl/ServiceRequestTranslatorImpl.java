/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.fhir2.api.translators.impl;

import static org.apache.commons.lang3.Validate.notNull;

import javax.annotation.Nonnull;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceAndListParam;
import ca.uhn.fhir.rest.param.ReferenceOrListParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import lombok.AccessLevel;
import lombok.Setter;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Specimen;
import org.hl7.fhir.r4.model.Task;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.Order;
import org.openmrs.Provider;
import org.openmrs.TestOrder;
import org.openmrs.api.ConceptService;
import org.openmrs.api.context.Context;
import org.openmrs.module.fhir2.api.FhirTaskService;
import org.openmrs.module.fhir2.api.translators.ConceptTranslator;
import org.openmrs.module.fhir2.api.translators.EncounterReferenceTranslator;
import org.openmrs.module.fhir2.api.translators.OrderIdentifierTranslator;
import org.openmrs.module.fhir2.api.translators.PatientReferenceTranslator;
import org.openmrs.module.fhir2.api.translators.PractitionerReferenceTranslator;
import org.openmrs.module.fhir2.api.translators.ServiceRequestTranslator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Setter(AccessLevel.PACKAGE)
public class ServiceRequestTranslatorImpl extends BaseReferenceHandlingTranslator implements ServiceRequestTranslator<TestOrder> {
	
	private static final int START_INDEX = 0;
	
	private static final int END_INDEX = 10;
	
	@Autowired
	private FhirTaskService taskService;
	
	@Autowired
	private ConceptTranslator conceptTranslator;
	
	@Autowired
	private PatientReferenceTranslator patientReferenceTranslator;
	
	@Autowired
	private EncounterReferenceTranslator<Encounter> encounterReferenceTranslator;
	
	@Autowired
	private PractitionerReferenceTranslator<Provider> providerReferenceTranslator;
	
	@Autowired
	private OrderIdentifierTranslator orderIdentifierTranslator;
	
	@Override
	public ServiceRequest toFhirResource(@Nonnull TestOrder order) {
		notNull(order, "The TestOrder object should not be null");
		
		ServiceRequest serviceRequest = new ServiceRequest();
		
		serviceRequest.setId(order.getUuid());
		
		//Add additional identifier fields as required
		
		// Include facility name
		serviceRequest.addIdentifier().setSystem("Facility_name")
		        .setValue(order.getEncounter().getLocation().getParentLocation().toString());
		
		//Include facility code
		serviceRequest.addIdentifier().setSystem("Facility_code").setValue(order.getEncounter().getLocation()
		        .getParentLocation().getActiveAttributes().stream().findFirst().get().getValueReference());
		
		//Include the order number to the ServiceRequest
		serviceRequest.addIdentifier().setSystem("eRegister Lab Order Number").setValue(LabOrderNumberGenerator());
		
		// Create a new Specimen resource and add it to the ServiceRequest as a contained resource
		Specimen labSpecimen = getSpecimen(order);
		serviceRequest.addContained(labSpecimen);
		serviceRequest.addSpecimen().setReference("#" + labSpecimen.getId());
		
		serviceRequest.setStatus(determineServiceRequestStatus(order));
		
		serviceRequest.setCode(conceptTranslator.toFhirResource(order.getConcept()));
		
		serviceRequest.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
		
		serviceRequest.setSubject(patientReferenceTranslator.toFhirResource(order.getPatient()));
		
		serviceRequest.setEncounter(encounterReferenceTranslator.toFhirResource(order.getEncounter()));
		
		serviceRequest.setRequester(providerReferenceTranslator.toFhirResource(order.getOrderer()));
		
		serviceRequest.setPerformer(Collections.singletonList(determineServiceRequestPerformer(order.getUuid())));
		
		serviceRequest
		        .setOccurrence(new Period().setStart(order.getEffectiveStartDate()).setEnd(order.getEffectiveStopDate()));
		
		serviceRequest.getMeta().setLastUpdated(order.getDateChanged());
		
		if (order.getPreviousOrder() != null
		        && (order.getAction() == Order.Action.DISCONTINUE || order.getAction() == Order.Action.REVISE)) {
			serviceRequest.setReplaces((Collections.singletonList(createOrderReference(order.getPreviousOrder())
			        .setIdentifier(orderIdentifierTranslator.toFhirResource(order.getPreviousOrder())))));
		} else if (order.getPreviousOrder() != null && order.getAction() == Order.Action.RENEW) {
			serviceRequest.setBasedOn(Collections.singletonList(createOrderReference(order.getPreviousOrder())
			        .setIdentifier(orderIdentifierTranslator.toFhirResource(order.getPreviousOrder()))));
		}
		
		return serviceRequest;
	}
	
	@Override
	public TestOrder toOpenmrsType(@Nonnull ServiceRequest resource) {
		throw new UnsupportedOperationException();
	}
	
	private ServiceRequest.ServiceRequestStatus determineServiceRequestStatus(TestOrder order) {
		
		Date currentDate = new Date();
		
		boolean isCompeted = order.isActivated()
		        && ((order.getDateStopped() != null && currentDate.after(order.getDateStopped()))
		                || (order.getAutoExpireDate() != null && currentDate.after(order.getAutoExpireDate())));
		boolean isDiscontinued = order.isActivated() && order.getAction() == Order.Action.DISCONTINUE;
		
		if ((isCompeted && isDiscontinued)) {
			return ServiceRequest.ServiceRequestStatus.UNKNOWN;
		} else if (isDiscontinued) {
			return ServiceRequest.ServiceRequestStatus.REVOKED;
		} else if (isCompeted) {
			return ServiceRequest.ServiceRequestStatus.COMPLETED;
		} else {
			return ServiceRequest.ServiceRequestStatus.ACTIVE;
		}
	}
	
	private Reference determineServiceRequestPerformer(String orderUuid) {
		IBundleProvider results = taskService.searchForTasks(
		    new ReferenceAndListParam()
		            .addAnd(new ReferenceOrListParam().add(new ReferenceParam("ServiceRequest", null, orderUuid))),
		    null, null, null, null, null, null);
		
		Collection<Task> serviceRequestTasks = results.getResources(START_INDEX, END_INDEX).stream().map(p -> (Task) p)
		        .collect(Collectors.toList());
		
		if (serviceRequestTasks.size() != 1) {
			return null;
		}
		
		return serviceRequestTasks.iterator().next().getOwner();
	}
	
	private String LabOrderNumberGenerator() {
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
		Random random = new Random();
		StringBuilder builder = new StringBuilder();
		
		//builder.append("LAB");
		/*for (int i = 0; i < 7; i++) {
		    builder.append(alphabet.charAt(random.nextInt(26)));
		}*/
		for (int i = 0; i < 4; i++) {
			builder.append(alphabet.charAt(random.nextInt(26)));
		}
		for (int i = 0; i < 6; i++) {
			builder.append(random.nextInt(10));
		}
		
		String labOrderNumber = builder.toString();
		System.out.println(labOrderNumber);
		
		return labOrderNumber;
	}
	
	private String getSpecimenType(Concept orderConcept) {
		
		ConceptService conceptService = Context.getConceptService();
		Concept labSamplesConcept = conceptService.getConceptByName("Lab Samples");
		List<Concept> labSamplesSet = labSamplesConcept.getSetMembers();
		
		for (Concept concept : labSamplesSet) {
			if (concept.getSetMembers().contains(orderConcept)) {
				return concept.getDisplayString();
			}
		}
		return "Cannot determine specimen type";
	}
	
	private Specimen getSpecimen(TestOrder order) {
		Specimen labSpecimen = new Specimen();
		labSpecimen.setId(new IdType("Specimen", UUID.randomUUID().toString()));
		String labSampleType = getSpecimenType(order.getConcept());
		labSpecimen.setType(new CodeableConcept()
		        .addCoding(new Coding().setSystem("Lab specimen type").setDisplay(labSampleType)).setText(labSampleType));
		labSpecimen.setCollection(
		    new Specimen.SpecimenCollectionComponent().setCollected(new DateTimeType(order.getCommentToFulfiller())));
		
		return labSpecimen;
	}
	
}
