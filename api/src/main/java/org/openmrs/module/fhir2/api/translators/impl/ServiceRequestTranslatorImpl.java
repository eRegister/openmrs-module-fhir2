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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
// import java.util.logging.Logger;
import java.util.regex.Pattern;
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
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Specimen;
import org.hl7.fhir.r4.model.Task;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.Patient;
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
// These guys are here for logging - debug
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
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
	
	//private static final Logger log = LoggerFactory.getLogger(ServiceRequestTranslatorImpl.class);
	
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
		//serviceRequest.addIdentifier().setSystem("Lab Order Number").setValue(generateLabOrderNumber(order));
		String labOrderNumber = generateLabOrderNumber(order);
		serviceRequest.setRequisition(new Identifier().setSystem("eRegister Lab order number").setValue(labOrderNumber));
		persistLabOrderNumber(order, labOrderNumber);
		// Create a new Specimen resource and add it to the ServiceRequest as a contained resource
		Specimen labSpecimen = getSpecimen(order);
		serviceRequest.addContained(labSpecimen);
		//serviceRequest.addSpecimen().setReference("#" + labSpecimen.getId());
		
		//Get a list of ARV Regimen, Preg status, Breastfeeding status & ART start date (of current regimen) Obs and link it in supporting info
		Map<String, Obs> supportingInfo = getSupportingInfo(order);
		//Map - value contains Obs and key contains a string refering to info in the Obs
		for (Map.Entry<String, Obs> entry : supportingInfo.entrySet()) {
			String obsDescription = entry.getKey();
			Obs obsItem = entry.getValue();
			if (obsItem != null) { //exclude empty obs
				serviceRequest.addSupportingInfo(
				    observationReferenceTranslator.toFhirResource(obsItem).setDisplay(obsDescription));
			}
		}
		
		//Add previous lab test results -- as a Diagnostic Report
		//serviceRequest.addSupportingInfo(getPrevResults(order.getConcept(), order.getPatient()));
		
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
			diagnosticReportReference = null;
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
	
	//Generate an order number of regex [A-Z]{4}[0-9]{6} based on the facility code & OpenMRS order number
	//[A-Z]{4} from the facility code & [0-9]{6} from the order number
	private String generateLabOrderNumber(Order order) {
		//transform facility code to 4 chars (should be deterministic & collision-free)
		//C1022 to [A-Z]{4} by doing a C + [A-Z]{3}
		String facilityCode = order.getEncounter().getLocation().getParentLocation().getActiveAttributes().stream()
		        .findFirst().get().getValueReference();
		String failityCodeHash = hashFacilityCode(facilityCode);
		
		String omrsOrderNum = order.getOrderNumber(); //e.g. ORD-1234
		omrsOrderNum = omrsOrderNum.replaceAll("\\D", ""); // remove non-digits
		int omrsOrderNumInt = Integer.parseInt(omrsOrderNum) % 1000000; //ensure it is less than 6 digits long
		omrsOrderNum = String.valueOf(omrsOrderNumInt);
		//prefix order num integer part with zeros if required
		StringBuilder paddedOmrsOrderNum = new StringBuilder(omrsOrderNum);
		int zerosToPad = 10 - (failityCodeHash.length() + omrsOrderNum.length());
		while (zerosToPad > 0) {
			paddedOmrsOrderNum.insert(0, "0");
			zerosToPad--;
		}
		return failityCodeHash + paddedOmrsOrderNum;
	}
	
	//generate a deterministic & collision-free 4 char code for each facility.
	private String hashFacilityCode(String facilityCode) {
		
		String pattern1 = "[A-Z]\\d{4}"; //most common pattern e.g. C1022 -> C + 1022nd 3char permutation
		String pattern2 = "[A-Z]\\d{5}"; //e.g. C10223 -> 10223rd 4char permutation
		char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
		if (Pattern.matches(pattern1, facilityCode)) {
			int intPart = Integer.parseInt(facilityCode.substring(1));
			intPart--; // Adjust int to start from 0 instead of 1
			char thirdChar = alphabet[intPart % 26];
			char secondChar = alphabet[(intPart / 26) % 26];
			char firstChar = alphabet[(intPart / 26 / 26) % 26];
			return facilityCode.charAt(0) + String.valueOf(firstChar) + String.valueOf(secondChar)
			        + String.valueOf(thirdChar);
		} else if (Pattern.matches(pattern2, facilityCode)) {
			int intPart = Integer.parseInt(facilityCode.substring(1));
			intPart--; // Adjust int to start from 0 instead of 1
			char forthChar = alphabet[intPart % 26];
			char thirdChar = alphabet[(intPart / 26) % 26];
			char secondChar = alphabet[(intPart / 26 / 26) % 26];
			//char firstChar = alphabet[(intPart / 26 / 26 / 26) % 26];
			return "Y" + String.valueOf(secondChar) + String.valueOf(thirdChar) + String.valueOf(forthChar);
		} else { //for all other patterns. e.g. POST-1, POST-10
			int intPart = Integer.parseInt(facilityCode.replaceAll("\\D", "")); //remove non-digits
			intPart--; // Adjust int to start from 0 instead of 1
			char thirdChar = alphabet[intPart % 26];
			char secondChar = alphabet[(intPart / 26) % 26];
			char firstChar = alphabet[(intPart / 26 / 26) % 26];
			return "Z" + String.valueOf(firstChar) + String.valueOf(secondChar) + String.valueOf(thirdChar);
		}
	}
	
	//Persist order number as an observation
	private void persistLabOrderNumber(Order order, String eregisterLabOrderNum) {
		//check if order num is already persisted
		Concept labOrderConcept = conceptService.getConceptByName("eRegister Lab Order Number");
		List<Obs> existingLabOrderNums = obsService.getObservationsByPersonAndConcept(order.getPatient(), labOrderConcept);
		if (!existingLabOrderNums.isEmpty()) {
			for (Obs observation : existingLabOrderNums) {
				if (observation.getValueText().equals(eregisterLabOrderNum)) {
					return;
				}
			}
		}
		//we'll get to this point if the lab order num is not yet persisted .. so persist it
		Obs newLabOrderObs = new Obs(order.getPatient(), labOrderConcept, order.getDateCreated(),
		        order.getEncounter().getLocation());
		newLabOrderObs.setEncounter(order.getEncounter());
		newLabOrderObs.setValueText(eregisterLabOrderNum);
		obsService.saveObs(newLabOrderObs, null);
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
		
		String VLSpecimenCollectionDate = "Specimen collection date & time";
		Obs specimenCollectionDateTimeObs = getObsFor(order.getPatient(), VLSpecimenCollectionDate, "last");
		if (specimenCollectionDateTimeObs != null) {
			labSpecimen.setCollection(new Specimen.SpecimenCollectionComponent()
			        .setCollected(new DateTimeType(specimenCollectionDateTimeObs.getValueDate())));
		}
		/* 
		labSpecimen.setCollection(
		    new Specimen.SpecimenCollectionComponent().setCollected(new DateTimeType(order.getCommentToFulfiller())));
		*/
		return labSpecimen;
	}
	
	private Map<String, Obs> getSupportingInfo(Order testOrder) {
		Map<String, Obs> supportingInfoObsMap = new LinkedHashMap<>();
		
		String testOrderConceptName = testOrder.getConcept().getName().getName();
		if (testOrderConceptName.contains("Viral")) {
			//get VL additional information
			supportingInfoObsMap = getSupportingInfoVL(testOrder.getPatient());
		} else if (testOrderConceptName.contains("TB")) {
			//get TB additional information
			if (testOrderConceptName.contains("Gene")) {
				supportingInfoObsMap = getSupportingInfoTBGeneX(testOrder.getPatient());
			}
		} else {
			//other tests ... yet to be added
		}
		
		return supportingInfoObsMap;
		
	}
	
	//Collect VL supporting information
	private Map<String, Obs> getSupportingInfoVL(org.openmrs.Patient pat) {
		// String ARTRegimenConceptName = "HIVTC, ART Regimen";
		String ARTStartConceptName = "HIVTC, ART start date";
		String pregStatusConceptName = "HIVTC, VL Pregnancy Status";
		String breastfeedingStatusConceptName = "HIVTC, VL Breastfeeding Status";
		String cd4ConceptName = "HIVTC, CD4";
		String vlReasonConceptName = "HIVTC, Viral Load Monitoring Type";
		
		Map<String, Obs> supportingInfoObsMap = new LinkedHashMap<>();
		
		//Get ART Regimens & add to hashmap
		List<Obs> firstDoseRegimensObs = getARTRegimens(pat);
		if (!firstDoseRegimensObs.isEmpty()) {
			if (firstDoseRegimensObs.size() == 1) {
				//only curr treatment is here
				supportingInfoObsMap.put("Current Regimen", firstDoseRegimensObs.get(0));
			} else {
				//we have curr & prev
				int pos = firstDoseRegimensObs.size() - 1;
				int count = 1;
				supportingInfoObsMap.put("Current Regimen", firstDoseRegimensObs.get(pos));
				for (pos = pos - 1; pos >= 0; pos--) {
					supportingInfoObsMap.put("Previous Regimen " + count, firstDoseRegimensObs.get(pos));
					count++;
				}
			}
		}
		//Add Previous VL results
		Obs prevVLResults = getPrevVLResult(pat);
		if (prevVLResults != null) {
			supportingInfoObsMap.put("Prev VL Results", prevVLResults);
		}
		//interested in only the latest observations - Pregnancy status & Breastfeeding status	
		supportingInfoObsMap.put("Pregnancy status", getObsFor(pat, pregStatusConceptName, "last"));
		supportingInfoObsMap.put("Breastfeeding status", getObsFor(pat, breastfeedingStatusConceptName, "last"));
		//interested in only the oldest/fisrt observation - ART start date
		supportingInfoObsMap.put("Current Regimen startdate", getObsFor(pat, ARTStartConceptName, "first"));
		//first and last cd4 results
		supportingInfoObsMap.put("First CD4", getObsFor(pat, cd4ConceptName, "first"));
		supportingInfoObsMap.put("Last CD4", getObsFor(pat, cd4ConceptName, "last"));
		//latest Reason for VL
		supportingInfoObsMap.put("VL Reason", getObsFor(pat, vlReasonConceptName, "last"));
		
		return supportingInfoObsMap;
	}
	
	//Gets previous viral load results (latest)
	private Obs getPrevVLResult(Patient pat) {
		String vlResultConceptName = "HIVTC, Viral Load Result";
		String vlDataConceptName = "HIVTC, Viral Load";
		Concept vlResultConcept = conceptService.getConceptByName(vlResultConceptName);
		List<Obs> allVlResultObs = obsService.getObservationsByPersonAndConcept(pat, vlResultConcept);
		if (!allVlResultObs.isEmpty()) {
			Obs lastVLResult = getLastObservation(allVlResultObs);
			
			if (lastVLResult.getValueCoded().getDisplayString().equals("Greater or equals to 20")) {
				Concept vlDataConcept = conceptService.getConceptByName(vlDataConceptName);
				List<Obs> allVlDataObs = obsService.getObservationsByPersonAndConcept(pat, vlDataConcept);
				Obs lastVLResultData = getLastObservation(allVlDataObs);
				return lastVLResultData;
			} else {
				return lastVLResult;
			}
		}
		return null; //patient does not have any prev vl results
		
	}
	
	//Gets either first or last Obs for a given concept
	private Obs getObsFor(Patient pat, String conceptName, String position) {
		Concept aConcept = conceptService.getConceptByName(conceptName);
		List<Obs> allObs = obsService.getObservationsByPersonAndConcept(pat, aConcept);
		if (!allObs.isEmpty()) {
			if (position.equals("first")) {
				return getFirstObservation(allObs);
			} else if (position.equals("last")) {
				return getLastObservation(allObs);
			} else {
				//shoudn't be here
				return null;
			}
		}
		return null;
	}
	
	//Gets 1st doses for all ART regimens
	private List<Obs> getARTRegimens(Patient pat) {
		String ARTRegimenConceptName = "HIVTC, ART Regimen";
		Concept ARTRegimenConcept = conceptService.getConceptByName(ARTRegimenConceptName);
		List<Obs> allARTRegimenObs = obsService.getObservationsByPersonAndConcept(pat, ARTRegimenConcept);
		List<Obs> deduplicatedRegimens = new ArrayList<Obs>();
		if (!allARTRegimenObs.isEmpty()) {
			//Sort collection by obsdate
			Collections.sort(allARTRegimenObs, new Comparator<Obs>() {
				
				@Override
				public int compare(Obs obs1, Obs obs2) {
					return obs1.getObsDatetime().compareTo(obs2.getObsDatetime());
				}
			});
			//essentially this is deduplication - linear search
			List<Concept> processedConcepts = new ArrayList<Concept>();
			
			deduplicatedRegimens.add(allARTRegimenObs.get(0));
			processedConcepts.add(deduplicatedRegimens.get(0).getValueCoded());
			for (Obs obs : allARTRegimenObs) {
				if (!processedConcepts.contains(obs.getValueCoded())) {
					processedConcepts.add(obs.getValueCoded());
					deduplicatedRegimens.add(obs);
				}
			}
			return deduplicatedRegimens;
		}
		return deduplicatedRegimens; //return an empty list
	}
	
	private Map<String, Obs> getSupportingInfoTBGeneX(org.openmrs.Patient pat) {
		String GenexReasonConceptName = "TB, Genexpert test type";
		Concept GenexReasonConcept = conceptService.getConceptByName(GenexReasonConceptName);
		List<Obs> allGenexReasonObs = obsService.getObservationsByPersonAndConcept(pat, GenexReasonConcept);
		Map<String, Obs> supportingInfoObsMap = new LinkedHashMap<>();
		supportingInfoObsMap.put(GenexReasonConceptName, getLastObservation(allGenexReasonObs));
		return supportingInfoObsMap;
	}
	
	private Obs getLastObservation(List<Obs> observations) {
		if (observations.size() == 0) {
			return null;
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
			return null;
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
