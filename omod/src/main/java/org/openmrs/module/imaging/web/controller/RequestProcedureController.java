/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.imaging.web.controller;

import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import org.openmrs.Patient;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.module.imaging.OrthancConfiguration;
import org.openmrs.module.imaging.api.DicomStudyService;
import org.openmrs.module.imaging.api.OrthancConfigurationService;
import org.openmrs.module.imaging.api.RequestProcedureService;
import org.openmrs.module.imaging.api.RequestProcedureStepService;
import org.openmrs.module.imaging.api.study.DicomStudy;
import org.openmrs.module.imaging.api.worklist.RequestProcedure;
import org.openmrs.module.imaging.api.worklist.RequestProcedureStep;
import org.openmrs.module.imaging.web.controller.ResponseModel.ProcedureStepResponse;
import org.openmrs.module.imaging.web.controller.ResponseModel.RequestProcedureResponse;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;

@Controller("${rootrootArtifactId}.RequestProcedureController")
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/worklist")
public class RequestProcedureController {
	
	protected Log log = LogFactory.getLog(this.getClass());
	
	private static final ObjectMapper mapper = new ObjectMapper();
	
	private static final int FUZZY_THRESHOLD = 98;
	
	@RequestMapping(value = "/requests", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> useRequestProcedures(
            @RequestParam(value = "status", required = false, defaultValue = "all") String status,
            HttpServletRequest request, HttpServletResponse response) {

        RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);

        Map<String, String> statusMapping = new HashMap<>();
        statusMapping.put("scheduled", "scheduled");
        statusMapping.put("progress", "in progress");
        statusMapping.put("completed", "completed");

        boolean filterAll = status == null || status.trim().isEmpty() || status.equalsIgnoreCase("all");
        String normalizedStatus = filterAll ? "" : status.trim().toLowerCase();

        // Determine the database status to query
        String dbStatus = filterAll ? "" : statusMapping.getOrDefault(normalizedStatus, status.trim());

        // Fetch requests
        List<RequestProcedure> requests = filterAll
                ? requestProcedureService.getAllRequestProcedures()
                : requestProcedureService.getRequestProceduresByStatus(dbStatus);

		RequestProcedureStepService requestProcedureStepService = Context.getService(RequestProcedureStepService.class);
        List<Map<String,Object>> result = new LinkedList<Map<String,Object>>();
        for (RequestProcedure rp : requests) {
            Map<String,Object> map = new HashMap<String,Object>();
            writeProcedure(rp, map, requestProcedureStepService);
            result.add(map);
        }
        return new ResponseEntity<>(result, HttpStatus.OK);
    }
	
	/**
	 * @param rp The request procedure object
	 * @param map The worklist data map
	 * @param requestProcedureStepService The request procedure step service
	 */
	private static void writeProcedure(RequestProcedure rp, Map<String, Object> map,
	        RequestProcedureStepService requestProcedureStepService) {

		map.put("SpecificCharacterSet", "ISO_IR 100");
		map.put("AccessionNumber", rp.getAccessionNumber());
		map.put("PatientName", rp.getMrsPatient().getPersonName().getFullName());
		map.put("PatientID", rp.getMrsPatient().getUuid());
		String birthDate = rp.getMrsPatient().getBirthdate().toString();
		String birthAge = rp.getMrsPatient().getAge().toString();
		if (birthDate == null || birthDate.trim().isEmpty()) {
			map.put("PatientBirthDate", birthAge);
		} else {
			map.put("PatientBirthDate", birthDate);
		}
		map.put("PatientSex", rp.getMrsPatient().getGender());
		map.put("StudyInstanceUID", rp.getStudyInstanceUID());
		map.put("RequestingPhysician", rp.getRequestingPhysician()); // RequestingPhysician
		map.put("RequestedProcedureDescription", rp.getRequestDescription());
		map.put("RequestedProcedureID", rp.getId().toString());
		map.put("RequestedProcedurePriority", rp.getPriority());

		// Read the procedure step
		List<RequestProcedureStep> procedureStep = requestProcedureStepService.getAllStepByRequestProcedure(rp);
		List<Map<String, Object>> stepList = new ArrayList<>();
		for(RequestProcedureStep step : procedureStep) {
			writeProcedureStep(step, stepList);
		}
		map.put("ScheduledProcedureStepSequence", stepList);
	}
	
	/**
	 * @param step The request procedure step
	 * @param stepList The list of the procedure step
	 */
	private static void writeProcedureStep(RequestProcedureStep step, List<Map<String, Object>> stepList) {
		Map<String, Object> stepMap = new HashMap<String, Object>();
		stepMap.put("Modality", step.getModality());
		stepMap.put("ScheduledStationAETitle", step.getAetTitle());
		stepMap.put("ScheduledProcedureStepStartDate", step.getStepStartDate());
		stepMap.put("ScheduledProcedureStepStartTime", step.getStepStartTime());
		stepMap.put("ScheduledPerformingPhysicianName", step.getScheduledPerformingPhysician());
		stepMap.put("PerformedProcedureStepStatus", step.getPerformedProcedureStepStatus());
		stepMap.put("ScheduledProcedureStepDescription", step.getRequestedProcedureDescription());
		stepMap.put("ScheduledProcedureStepID", step.getId().toString());
		stepMap.put("ScheduledStationName", step.getStationName());
		stepMap.put("ScheduledProcedureStepLocation", step.getProcedureStepLocation());
		stepMap.put("CommentsOnTheScheduledProcedureStep", "no value available");
		stepList.add(stepMap);
	}
	
	/**
	 * @param payload The whole study data procedure that has been performed in this step.
	 */
	@RequestMapping(value = "/updaterequeststatus", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	@Transactional
    public ResponseEntity<?> updateRequestStatus(HttpServletRequest request, HttpServletResponse response,
                                                                @RequestBody StudyUpdatePayload payload) throws IOException {
        RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);
		RequestProcedureStepService requestProcedureStepService = Context.getService(RequestProcedureStepService.class);

//        System.out.println("All payload:\n" +
//                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload));

        log.info("All payload: " + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));

        // Study-level UID from JSON payload
        String studyInstanceUID = payload.getStudyInfo().getStudyInstanceUID();

        // Process every series sent by Orthanc
        for (StudyUpdatePayload.SeriesEntry entry: payload.getSeriesList()) {
            String scheduledProcedureStepID = entry.getScheduledProcedureStepID();

            log.info("Procedure step: " +  scheduledProcedureStepID);

            if (scheduledProcedureStepID == null) {
                continue;
            }

            // Fetch the step
            int stepId;
            try {
                stepId = Integer.parseInt(scheduledProcedureStepID);
            } catch (NumberFormatException e) {
                log.warn("Invalid step ID: " + scheduledProcedureStepID);
                continue;
            }
            RequestProcedureStep step =
                    requestProcedureStepService.getProcedureStep(stepId);

            if (step != null && step.getRequestProcedure() != null) {
                // Update the procedure step status
                if (!step.getPerformedProcedureStepStatus().equals("rejected")) {
                    requestProcedureStepService.updatePerformedProcedureStepStatus(step, "completed");
                } else {
                    continue;
                }

                // Set the study instance UID created by modality device
                step.getRequestProcedure().setStudyInstanceUID(studyInstanceUID);
                requestProcedureStepService.updateProcedureStep(step);

                // Check all procedure step perform status of the request
                RequestProcedure requestProcedure = step.getRequestProcedure();
                List<RequestProcedureStep> stepList = requestProcedureStepService.getAllStepByRequestProcedure(requestProcedure);

                if (!stepList.isEmpty()) {
                    boolean allCompletedOrRejected = stepList.stream()
                            .allMatch(s -> {
                                String status = s.getPerformedProcedureStepStatus().trim();
                                return "completed".equalsIgnoreCase(status)
                                        || "rejected".equalsIgnoreCase(status);
                            });
                    log.info("All steps of procedure completed: " +  allCompletedOrRejected);

                    // compare metadata
                    ComparisonResult comparisonResult = compareWorklistStudyData(requestProcedure, stepList, payload);
                    assignRequestProceduredStudyToPatient(requestProcedure, payload, comparisonResult);

                    if (allCompletedOrRejected) {
                        requestProcedure.setStatus("completed");
                        requestProcedureService.updateRequestStatus(requestProcedure);
                    }
                    return ResponseEntity.ok(comparisonResult);
                } else {
                    return ResponseEntity.ok("Steps updated, but not all completed");
                }
            } else {
                return ResponseEntity.ok("No valid procedure step IDs found in payload");
            }
        }
        return ResponseEntity.ok("No series data found in payload");
    }
	
	/**
	 * @param requestProcedure The procedure for requesting patient image data.
	 * @param payload The metadata of image study for comparison
	 * @param comparisonResult The result of comparing the metadata from the Image Study with that
	 *            from OpenMRS.
	 * @throws IOException
	 */
	private void assignRequestProceduredStudyToPatient (RequestProcedure requestProcedure,
                                                        StudyUpdatePayload payload, ComparisonResult comparisonResult)
	        throws IOException {
		DicomStudyService dicomStudyService = Context.getService(DicomStudyService.class);
		Patient patient = requestProcedure.getMrsPatient();
		OrthancConfiguration config = requestProcedure.getOrthancConfiguration();

		dicomStudyService.fetchNewChangedStudiesByConfiguration(config);
		List<DicomStudy> studies = dicomStudyService.getStudiesByConfiguration(config);

        String studyUID = payload.getStudyInfo().getStudyInstanceUID();

        DicomStudy study = (studies == null || studies.isEmpty())
                ? null
                : studies.stream()
                .filter(s -> studyUID.equals(s.getStudyInstanceUID()))
                .findFirst()
                .orElse(null);

        if (study != null && comparisonResult != null) {
            int score = comparisonResult.getScore();

            if (score == 100) {
                dicomStudyService.updateLinkStatus(study, 2);
            } else {
                dicomStudyService.updateLinkStatus(study, 1);
            }

            String json = mapper.writeValueAsString(comparisonResult);
            study.setComparisonResult(json);
            study.setMrsPatient(patient);
        }
	}
	
	/**
	 * @param requestProcedure The procedure for requesting patient image data.
	 * @param stepList The procedure steps of the request procedure
	 * @param payload The metadata of image study for comparison
	 */
	private ComparisonResult compareWorklistStudyData (
            RequestProcedure requestProcedure,
            List<RequestProcedureStep> stepList,
            StudyUpdatePayload payload) {

        int score = 0;
        List<DicomDifference> diffs = new ArrayList<>();

        if (requestProcedure == null || payload == null || payload.getStudyInfo() == null) {
            return new ComparisonResult(score, diffs); // Nothing to compare
        }

        // 1. Study-level comparison
        String accessionDB = requestProcedure.getAccessionNumber();
        String accessionPayload = payload.getStudyInfo().getAccessionNumber();
        if (isNotBlank(accessionDB) && isNotBlank(accessionPayload) &&
                accessionDB.equalsIgnoreCase(accessionPayload)) {
            score += 10;
        } else {
            diffs.add(new DicomDifference("AccessionNumber", accessionDB, accessionPayload));
        }

        // referringPhysicianName
        String requestingPhysicianDB = requestProcedure.getRequestingPhysician();
        String requestingPhysicianPayload = payload.getStudyInfo().getReferringPhysicianName();
        if (isFuzzyMatch(requestingPhysicianDB, requestingPhysicianPayload, FUZZY_THRESHOLD)) {
            diffs.add(new DicomDifference("RequestingPhysician", requestingPhysicianDB, requestingPhysicianPayload));
        } else {
            score += 10;
        }

        //2. Step-level comparison
        if (stepList != null && !stepList.isEmpty() && payload.getSeriesList() != null) {
            int stepScoreTotal = 0;
            int maxStepScorePerStep = 100;
            int normalizedStepMax = 80;

            for (RequestProcedureStep step : stepList) {
                StudyUpdatePayload.SeriesEntry entry = payload.getSeriesList().stream()
                        .filter(s -> step.getId() != null
                                && s.getScheduledProcedureStepID() != null
                                && step.getId().toString().equalsIgnoreCase(s.getScheduledProcedureStepID()))
                        .findFirst()
                        .orElse(null);
                if (entry == null) { continue; }

                int stepScore = 0;

                // Extract entry components safely
                StudyUpdatePayload.InstanceInfo inst = entry.getInstanceInfo();
                String patientNameDB = getPatientNameDB(step);

                StudyUpdatePayload.SeriesInfo series = entry.getSeriesInfo();
                String patientNamePayload = inst != null
                        ? inst.getPatientName()
                        : null;
                String normalizedPatientNamePayload = patientNamePayload != null
                        ? patientNamePayload.replace("^", " ").trim()
                        : "";

                if (isFuzzyMatch(patientNameDB, normalizedPatientNamePayload, FUZZY_THRESHOLD)) {
                    diffs.add(new DicomDifference("PatientName", patientNameDB, normalizedPatientNamePayload, step.getId().toString()));
                } else {
                    stepScore += 15;
                }

                // Patient ID
                Patient patient = step.getRequestProcedure() != null
                        ? step.getRequestProcedure().getMrsPatient()
                        : null;

                String patientIdDB = patient != null && patient.getPatientId() != null
                        ? patient.getPatientId().toString()
                        : null;

                String patientIdPayload = inst != null ? inst.getPatientID() : null;

                if (isNotBlank(patientIdPayload) && patientIdPayload.equalsIgnoreCase(patientIdDB)){
                    stepScore += 10;
                } else {
                    diffs.add(new DicomDifference("PatientID",  patientIdDB, patientIdPayload));
                }

                // Patient birthdate
                Date birthDate = patient != null && patient.getBirthdate() != null
                        ? patient.getBirthdate()
                        : null;

                String patientBirthDateDB = null;
                if (birthDate != null) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
                    patientBirthDateDB = sdf.format(birthDate);
                }

                String patientBirthDatePayload = entry.getInstanceInfo() != null
                        ? entry.getInstanceInfo().getPatientBirthDate()
                        : null;

                if (isNotBlank(patientBirthDatePayload) && patientBirthDatePayload.equalsIgnoreCase(patientBirthDateDB)) {
                    stepScore += 15;
                } else {
                    diffs.add(new DicomDifference("PatientBirthDate", patientBirthDateDB, patientBirthDatePayload));
                }

                // Modality
                String modalityDB = step.getModality();
                String modalityPayload = series != null ? series.getModality() : null;

                if (isNotBlank(modalityDB) && modalityDB.equalsIgnoreCase(modalityPayload)) {
                    stepScore += 10;
                } else {
                    diffs.add(new DicomDifference("Modality", modalityDB, modalityPayload, step.getId().toString()));
                }

                // Scheduled performing physician
                String scheduledPhysicianDB = step.getScheduledPerformingPhysician();
                String scheduledPhysicianPayload = inst != null ? inst.getScheduledPerformingPhysician() : null;
                if (isFuzzyMatch(scheduledPhysicianDB, scheduledPhysicianPayload, FUZZY_THRESHOLD)) {
                    diffs.add(new DicomDifference("ScheduledPerformingPhysician", scheduledPhysicianDB, scheduledPhysicianPayload, step.getId().toString()));
                } else {
                    stepScore += 10;
                }

                // Requested procedure description
                String requestedProcedureDB = step.getRequestedProcedureDescription();
                String performedProcedurePayload = inst != null ? inst.getPerformedProcedureStepDescription() : null;
                if (isFuzzyMatch(requestedProcedureDB, performedProcedurePayload, FUZZY_THRESHOLD)) {
                    diffs.add(new DicomDifference("PerformedProcedureStepDescription", requestedProcedureDB, performedProcedurePayload, step.getId().toString()));
                } else {
                    stepScore += 10;
                }

                // Station Name
                String stationDB = step.getStationName();
                String stationPayload = series != null ? series.getStationName() : null;

                if (isFuzzyMatch(stationDB, stationPayload, FUZZY_THRESHOLD)) {
                    diffs.add(new DicomDifference("StationName", stationDB, stationPayload, step.getId().toString()));
                } else {
                    stepScore += 10;
                }

                stepScoreTotal += stepScore;
            }

            int totalPossibleStepPoints = stepList.size() * maxStepScorePerStep;
            if (totalPossibleStepPoints > 0 ) {
                int normalizedStepScore = (int)((double) stepScoreTotal / totalPossibleStepPoints * normalizedStepMax);
                score += normalizedStepScore;
            }
        }
        return new ComparisonResult(score, diffs);
    }
	
	private boolean isFuzzyMatch(String a, String b, int threshold) {
		if (isNotBlank(a) && isNotBlank(b)) {
			int score = FuzzySearch.tokenSetRatio(a.toLowerCase(Locale.ROOT), b.toLowerCase(Locale.ROOT));
			return score < threshold;
		}
		return true;
	}
	
	/**
	 * @param step The procedure step of worklist request
	 * @return The retrieved patient name
	 */
	private static String getPatientNameDB(RequestProcedureStep step) {
		Patient patient = step.getRequestProcedure() != null ? step.getRequestProcedure().getMrsPatient() : null;
		
		String givenNameDB = patient != null && patient.getGivenName() != null ? patient.getGivenName().trim() : "";
		
		String familyNameDB = patient != null && patient.getFamilyName() != null ? patient.getFamilyName().trim() : "";
		return (givenNameDB + " " + familyNameDB).trim();
	}
	
	@RequestMapping(value = "/updateprocedurestepstatus", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<?> updateProcedureStepStatus(
            @RequestParam(value="stepId") int stepId,
            @RequestParam(value="status") String status,
            HttpServletRequest request, HttpServletResponse response ) {

        RequestProcedureStepService requestProcedureStepService = Context.getService(RequestProcedureStepService.class);
        RequestProcedureStep step = requestProcedureStepService.getProcedureStep(stepId);
        if (stepId <= 0) {
            return new ResponseEntity<>("step ID is missing", HttpStatus.BAD_REQUEST);
        } else {
            requestProcedureStepService.updatePerformedProcedureStepStatus(step, status);
        }
        return new ResponseEntity<>("", HttpStatus.OK);
    }
	
	/**
	 * @param requestPostData The data for the new request procedure
	 * @return The response entity resulting from the request processing
	 */
	@RequestMapping(value = "/saverequest", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	@Transactional
	public ResponseEntity<Object> saveRequestProcedure(@RequestBody Map<String, Object> requestPostData,
													  HttpServletRequest request, HttpServletResponse response ) {

		RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);

		PatientService patientService = Context.getPatientService();
		String patientUuid = (String) requestPostData.get("patientUuid");
		Patient patient = patientService.getPatientByUuid(patientUuid);

		OrthancConfigurationService orthancConfigurationService = Context.getService(OrthancConfigurationService.class);
		OrthancConfiguration configuration = orthancConfigurationService.getOrthancConfiguration((Integer) requestPostData.get("configurationId"));

		RequestProcedure newReq = new RequestProcedure();
		newReq.setStatus("scheduled");
		newReq.setMrsPatient(patient);
		newReq.setOrthancConfiguration(configuration);
		newReq.setAccessionNumber((String) requestPostData.get("accessionNumber"));
		newReq.setStudyInstanceUID(null);
		newReq.setRequestingPhysician((String) requestPostData.get("requestingPhysician"));
		newReq.setRequestDescription((String) requestPostData.get("requestDescription"));
		newReq.setPriority((String) requestPostData.get("priority"));
		try{
			requestProcedureService.newRequest(newReq);
			return new ResponseEntity<>("", HttpStatus.OK);
		} catch (IOException e) {
			return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}
	
	/**
	 * @param stepPostData The data for the procedure step
	 * @return The response entity resulting from the request processing
	 */
	@RequestMapping(value = "/savestep", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	@Transactional
	public ResponseEntity<Object> saveRequestProcedureStep(@RequestBody Map<String, Object> stepPostData,
													   HttpServletRequest request,
													   HttpServletResponse response ) {
		RequestProcedureStepService requestProcedureStepService = Context.getService(RequestProcedureStepService.class);
		RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);

		int requestId = (Integer) stepPostData.get("requestId");
		RequestProcedure requestProcedure = requestProcedureService.getRequestProcedure(requestId);

		RequestProcedureStep newStep = new RequestProcedureStep();
		newStep.setRequestProcedure(requestProcedure);
		newStep.setModality((String) stepPostData.get("modality"));
		newStep.setAetTitle((String) stepPostData.get("aetTitle"));
		newStep.setScheduledPerformingPhysician((String) stepPostData.get("scheduledPerformingPhysician"));
		newStep.setRequestedProcedureDescription((String) stepPostData.get("requestedProcedureDescription"));
		newStep.setPerformedProcedureStepStatus("scheduled");
		newStep.setStepStartDate((String) stepPostData.get("stepStartDate"));
		newStep.setStepStartTime((String) stepPostData.get("stepStartTime"));
		newStep.setStationName((String) stepPostData.get("stationName"));
		newStep.setProcedureStepLocation((String) stepPostData.get("procedureStepLocation"));

		try{
			requestProcedureStepService.newProcedureStep(newStep);
			requestProcedure.setStatus("progress");
			requestProcedureService.updateRequestStatus(requestProcedure);

			return new ResponseEntity<>("", HttpStatus.OK);
		} catch (IOException e) {
			return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	/**
	 * @param patientUuid The patient unique ID
	 * @return The response entity resulting from the request processing
	 */
	@RequestMapping(value = "/patientrequests", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	@Transactional
	public ResponseEntity<Object> useRequestsByPatient(@RequestParam("patient") String patientUuid,
													   HttpServletRequest request, HttpServletResponse response ) {
        RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);
        PatientService patientService = Context.getPatientService();
        Patient patient = patientService.getPatientByUuid(patientUuid);

        List<RequestProcedure> requests = requestProcedureService.getRequestProcedureByPatient(patient);
        List<RequestProcedureResponse> requestProcedureResponseList = new ArrayList<>();
        for (RequestProcedure req : requests) {
            RequestProcedureResponse reqRes = RequestProcedureResponse.createResponse(req);
            requestProcedureResponseList.add(reqRes);
        }
        return new ResponseEntity<>(requestProcedureResponseList, HttpStatus.OK);
    }
	
	/**
	 * @param requestId The request procedure ID
	 * @return The retrieved procedure step list
	 */
	@RequestMapping(value = "/requeststep", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	@Transactional
	public ResponseEntity<Object> useProcedureStep(@RequestParam("requestId") int requestId,
												   HttpServletRequest request,
												   HttpServletResponse response ) {
		RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);
		RequestProcedureStepService requestProcedureStepService = Context.getService(RequestProcedureStepService.class);
		RequestProcedure req = requestProcedureService.getRequestProcedure(requestId);
		List<RequestProcedureStep> steps = requestProcedureStepService.getAllStepByRequestProcedure(req);

		List<ProcedureStepResponse> procedureStepResponseList = steps.stream().map(ProcedureStepResponse::createResponse).collect(Collectors.toList());
		return new ResponseEntity<>(procedureStepResponseList, HttpStatus.OK);
	}
	
	/**
	 * @param requestId The request procedure ID
	 * @return The response entity
	 */
	@RequestMapping(value = "/request", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
	@Transactional
	public ResponseEntity<Object> deleteRequest(@RequestParam(value="requestId") int requestId,
											   HttpServletRequest request,
											   HttpServletResponse response ) {
		RequestProcedureService requestProcedureService = Context.getService(RequestProcedureService.class);
		RequestProcedureStepService requestProcedureStepService = Context.getService(RequestProcedureStepService.class);
		RequestProcedure requestProcedure = requestProcedureService.getRequestProcedure(requestId);

		List<RequestProcedureStep> stepList = requestProcedureStepService.getAllStepByRequestProcedure(requestProcedure);
		if (!stepList.isEmpty()) {
			try {
				for (RequestProcedureStep step : stepList) {
					requestProcedureStepService.deleteProcedureStep(step);
				}
			} catch (IOException e) {
				return new ResponseEntity<>("", HttpStatus.INTERNAL_SERVER_ERROR);
			}
		}
		try {
			requestProcedureService.deleteRequestProcedure(requestProcedure);
			return new ResponseEntity<>("", HttpStatus.OK);
		}catch (IOException e) {
			return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	/**
	 * @param stepId The procedure step of the request
	 * @param request The request of procedure
	 * @return The response entity
	 */
	@RequestMapping(value = "/requeststep", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
	@Transactional
	public ResponseEntity<Object> deleteProcedureStep(@RequestParam(value="stepId") int stepId,
											   HttpServletRequest request,
											   HttpServletResponse response ) {

		RequestProcedureStepService requestProcedureStepService = Context.getService(RequestProcedureStepService.class);
		RequestProcedureStep step = requestProcedureStepService.getProcedureStep(stepId);

		try {
			requestProcedureStepService.deleteProcedureStep(step);
			return new ResponseEntity<>("", HttpStatus.OK);
		}catch (IOException e) {
			return new ResponseEntity<>("", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
}
