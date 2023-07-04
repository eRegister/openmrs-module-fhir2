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
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import ca.uhn.fhir.rest.api.SortOrderEnum;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceAndListParam;
import ca.uhn.fhir.rest.param.ReferenceOrListParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import lombok.AccessLevel;
import lombok.Setter;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Specimen;
import org.hl7.fhir.r4.model.Task;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.Provider;
import org.openmrs.TestOrder;
import org.openmrs.api.ConceptService;
import org.openmrs.api.ObsService;
import org.openmrs.module.fhir2.FhirConstants;
import org.openmrs.module.fhir2.api.FhirDiagnosticReportService;
import org.openmrs.module.fhir2.api.FhirTaskService;
import org.openmrs.module.fhir2.api.translators.ConceptTranslator;
import org.openmrs.module.fhir2.api.translators.EncounterReferenceTranslator;
import org.openmrs.module.fhir2.api.translators.ObservationReferenceTranslator;
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
	private FhirDiagnosticReportService diagnosticReportService;
	
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
	
	@Autowired
	private ObservationReferenceTranslator observationReferenceTranslator;
	
	@Autowired
	private ConceptService conceptService;
	
	@Autowired
	private ObsService obsService;
	
	@Override
	public ServiceRequest toFhirResource(@Nonnull TestOrder order) {
		notNull(order, "The TestOrder object should not be null");
		
		ServiceRequest serviceRequest = new ServiceRequest();
		
		serviceRequest.setId(order.getUuid());
		
		//Add additional identifier fields as required
		
		//Include order id -- required by OpenELIS
		serviceRequest.addIdentifier().setSystem("ServiceReq_id").setValue(order.getUuid());
		
		// Include facility name
		serviceRequest.addIdentifier().setSystem("Facility_name")
		        .setValue(order.getEncounter().getLocation().getParentLocation().toString());
		
		//Include facility code
		serviceRequest.addIdentifier().setSystem("Facility_code").setValue(order.getEncounter().getLocation()
		        .getParentLocation().getActiveAttributes().stream().findFirst().get().getValueReference());
		
		//Include the order number to the ServiceRequest
		//serviceRequest.addIdentifier().setSystem("eRegister Lab Order Number").setValue(LabOrderNumberGenerator());
		
		// Create a new Specimen resource and add it to the ServiceRequest as a contained resource
		Specimen labSpecimen = getSpecimen(order);
		serviceRequest.addContained(labSpecimen);
		//serviceRequest.addSpecimen().setReference("#" + labSpecimen.getId());
		
		//Get a list of ARV Regimen, Preg status, Breastfeeding status & ART start date (of current regimen) Obs and link it in supporting info
		Map<String, Obs> supportingInfo = getSupportingInfo(order.getPatient());
		//Map - value contains Obs and key contains a string refering to info in the Obs
		for (Map.Entry<String, Obs> entry : supportingInfo.entrySet()) {
			String obsDescription = entry.getKey();
			Obs obsItem = entry.getValue();
			serviceRequest
			        .addSupportingInfo(observationReferenceTranslator.toFhirResource(obsItem).setDisplay(obsDescription));
		}
		
		serviceRequest.addSupportingInfo(getPrevResults(order.getConcept(), order.getPatient()));
		
		//Add requisition id
		//serviceRequest.setRequisition(
		//    new Identifier().setSystem("Requisition_id").setValue(determineCommonRequisitionId(order.getUuid())));
		
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
	
	private Reference getPrevResults(Concept oderConcept, org.openmrs.Patient pat) {
		
		//Get Results for concept and patient
		ReferenceAndListParam patientRef = new ReferenceAndListParam()
		        .addAnd(new ReferenceOrListParam().add(new ReferenceParam("Patient", null, pat.getUuid())));
		TokenAndListParam oderCode = new TokenAndListParam().addAnd(new TokenParam(oderConcept.getUuid()));
		SortSpec sortSpec = new SortSpec().setParamName("_lastupdated").setOrder(SortOrderEnum.DESC);
		
		IBundleProvider results = diagnosticReportService.searchForDiagnosticReports(null, patientRef, null, oderCode, null,
		    null, null, sortSpec, null);
		
		List<IBaseResource> prevReports = results.getAllResources();
		Reference diagnosticReportReference;
		String resultName = oderConcept.getDisplayString();
		//get first element
		if (prevReports.size() > 0) {
			String reportId = prevReports.get(0).getIdElement().getIdPart();
			diagnosticReportReference = new Reference().setReference(FhirConstants.DIAGNOSTIC_REPORT + "/" + reportId)
			        .setType(FhirConstants.DIAGNOSTIC_REPORT).setDisplay(resultName);
		} else {
			diagnosticReportReference = new Reference().setReference(FhirConstants.DIAGNOSTIC_REPORT + "/" + "null")
			        .setType(FhirConstants.DIAGNOSTIC_REPORT).setDisplay(resultName);
		}
		
		return diagnosticReportReference;
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
	/* 
	private String determineCommonRequisitionId(String orderUuid) {
		IBundleProvider results = taskService.searchForTasks(
		    new ReferenceAndListParam()
		            .addAnd(new ReferenceOrListParam().add(new ReferenceParam("ServiceRequest", null, orderUuid))),
		    null, null, null, null, null, null);
		
		Collection<Task> serviceRequestTasks = results.getResources(START_INDEX, END_INDEX).stream().map(p -> (Task) p)
		        .collect(Collectors.toList());
		
		if (serviceRequestTasks.size() != 1) { //should be a single task
			return "";
		}
		
		for (Identifier identifier : serviceRequestTasks.iterator().next().getIdentifier()) {
			if (identifier.getSystem() == "eRegister Lab Order Number") {
				return identifier.getValue();
			}
		}
		return "";
	}
	*/
	
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
		
		//ConceptService conceptService = Context.getConceptService();
		Concept labSamplesConcept = conceptService.getConceptByName("Lab Samples");
		List<Concept> labSamplesSet = labSamplesConcept.getSetMembers();
		
		for (Concept concept : labSamplesSet) {
			if (concept.getSetMembers().contains(orderConcept)) {
				return concept.getDisplayString();
			}
		}
		return "Cannot determine specimen type";
	}
	
	//construct specimen
	private Specimen getSpecimen(TestOrder order) {
		Specimen labSpecimen = new Specimen();
		labSpecimen.setId(new IdType("Specimen", UUID.randomUUID().toString()));
		String labSampleType = getSpecimenType(order.getConcept());
		labSpecimen.setType(new CodeableConcept()
		        .addCoding(new Coding().setSystem("Lab specimen type").setDisplay(labSampleType)).setText(labSampleType));
		
		String VLSpecimenCollectionDate = "HIVTC, Viral Load Blood drawn date";
		Date VLSamplecollectionDate = getLastObservation(obsService.getObservationsByPersonAndConcept(order.getPatient(),
		    conceptService.getConcept(VLSpecimenCollectionDate))).getValueDate();
		
		labSpecimen.setCollection(
		    new Specimen.SpecimenCollectionComponent().setCollected(new DateTimeType(VLSamplecollectionDate)));
		/* 
		labSpecimen.setCollection(
		    new Specimen.SpecimenCollectionComponent().setCollected(new DateTimeType(order.getCommentToFulfiller())));
		*/
		
		return labSpecimen;
	}
	
	//get ARV Regimen, Pregnancy, Breastfeeding, etc. Obs for patient whose order is being placed
	private Map<String, Obs> getSupportingInfo(org.openmrs.Patient pat) {
		String ARTRegimenConceptName = "HIVTC, ART Regimen";
		String ARTStartConceptName = "HIVTC, ART start date";
		String pregStatusConceptName = "HIVTC, VL Pregnancy Status";
		String breastfeedingStatusConceptName = "HIVTC, VL Breastfeeding Status";
		String startOnCurrRegimen = "Start date for Current ART Regimen";
		String cd4ConceptName = "HIVTC, CD4";
		String vlReason = "HIVTC, Viral Load Monitoring Type";
		
		//get Obs for these concepts made for this patient (parameter)
		
		Concept ARTRegimenConcept = conceptService.getConceptByName(ARTRegimenConceptName);
		Concept ARTStartConcept = conceptService.getConceptByName(ARTStartConceptName);
		Concept pregConcept = conceptService.getConceptByName(pregStatusConceptName);
		Concept breastfeedingConcept = conceptService.getConceptByName(breastfeedingStatusConceptName);
		Concept cd4Concept = conceptService.getConceptByName(cd4ConceptName);
		Concept vlReasonConcept = conceptService.getConceptByName(vlReason);
		
		List<Obs> allArvRegimensObs = obsService.getObservationsByPersonAndConcept(pat, ARTRegimenConcept);
		List<Obs> allArtStartObs = obsService.getObservationsByPersonAndConcept(pat, ARTStartConcept);
		List<Obs> allPregObs = obsService.getObservationsByPersonAndConcept(pat, pregConcept);
		List<Obs> allBreastfeedingObs = obsService.getObservationsByPersonAndConcept(pat, breastfeedingConcept);
		List<Obs> allCd4Obs = obsService.getObservationsByPersonAndConcept(pat, cd4Concept);
		List<Obs> allVlReasonObs = obsService.getObservationsByPersonAndConcept(pat, vlReasonConcept);
		
		Concept currentARTRegimenConcept = getLastObservation(allArvRegimensObs).getValueCoded();
		
		Map<String, Obs> supportingInfoObsMap = new LinkedHashMap<>();
		
		//interested in only the latest observations - ART Regimen, Pregnancy status & Breastfeeding status	
		supportingInfoObsMap.put(ARTRegimenConceptName, getLastObservation(allArvRegimensObs));
		supportingInfoObsMap.put(startOnCurrRegimen, getFirstObsForCurrRegimen(allArvRegimensObs, currentARTRegimenConcept));
		supportingInfoObsMap.put(pregStatusConceptName, getLastObservation(allPregObs));
		supportingInfoObsMap.put(breastfeedingStatusConceptName, getLastObservation(allBreastfeedingObs));
		//interested in only the oldest/fisrt observation - ART start date
		supportingInfoObsMap.put(ARTStartConceptName, getFirstObservation(allArtStartObs));
		//first and last cd4 results
		supportingInfoObsMap.put("First " + cd4ConceptName, getFirstObservation(allCd4Obs));
		supportingInfoObsMap.put("Last " + cd4ConceptName, getLastObservation(allCd4Obs));
		//Reason for VL
		supportingInfoObsMap.put(vlReason, getLastObservation(allVlReasonObs));
		
		return supportingInfoObsMap;
	}
	
	//This is essentially a linear search ... needs better perfomance optimization
	private Obs getFirstObsForCurrRegimen(List<Obs> ARTRegimenObs, Concept currRegimen) {
		Obs emptyObs = new Obs();
		emptyObs.setUuid(null);
		
		for (Obs obs : ARTRegimenObs) {
			if (obs.getValueCoded().equals(currRegimen)) {
				return obs;
			}
		}
		return emptyObs;
	}
	
	private Obs getLastObservation(List<Obs> observations) {
		if (observations.size() == 0) {
			Obs emptyObs = new Obs();
			emptyObs.setUuid(null);
			return emptyObs;
		} else {
			// Sort the list by date created
			Collections.sort(observations, new Comparator<Obs>() {
				
				@Override
				public int compare(Obs obs1, Obs obs2) {
					//return obs1.getDateCreated().compareTo(obs2.getDateCreated());
					return obs1.getObsDatetime().compareTo(obs2.getObsDatetime());
				}
			});
			return observations.get(observations.size() - 1);
		}
	}
	
	private Obs getFirstObservation(List<Obs> observations) {
		if (observations.size() == 0) {
			Obs emptyObs = new Obs();
			emptyObs.setUuid(null);
			return emptyObs;
		} else {
			// Sort the list by date created
			Collections.sort(observations, new Comparator<Obs>() {
				
				@Override
				public int compare(Obs obs1, Obs obs2) {
					//return obs1.getDateCreated().compareTo(obs2.getDateCreated());
					return obs1.getObsDatetime().compareTo(obs2.getObsDatetime());
				}
			});
			return observations.get(0);
		}
	}
	
}
