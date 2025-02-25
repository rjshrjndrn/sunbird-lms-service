package org.sunbird.actor.bulkupload;

import akka.actor.ActorRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.client.location.LocationClient;
import org.sunbird.client.location.impl.LocationClientImpl;
import org.sunbird.client.org.OrganisationClient;
import org.sunbird.client.org.impl.OrganisationClientImpl;
import org.sunbird.client.systemsettings.SystemSettingClient;
import org.sunbird.client.systemsettings.impl.SystemSettingClientImpl;
import org.sunbird.exception.ResponseCode;
import org.sunbird.keys.JsonKey;
import org.sunbird.model.bulkupload.BulkUploadProcess;
import org.sunbird.model.bulkupload.BulkUploadProcessTask;
import org.sunbird.model.location.Location;
import org.sunbird.model.organisation.OrgTypeEnum;
import org.sunbird.model.organisation.Organisation;
import org.sunbird.request.Request;
import org.sunbird.request.RequestContext;
import org.sunbird.telemetry.dto.TelemetryEnvKey;
import org.sunbird.util.ProjectUtil;
import org.sunbird.util.Util;

public class OrgBulkUploadBackgroundJobActor extends BaseBulkUploadBackgroundJobActor {

  private OrganisationClient orgClient = new OrganisationClientImpl();
  private SystemSettingClient systemSettingClient = new SystemSettingClientImpl();

  @Inject
  @Named("system_settings_actor")
  private ActorRef systemSettingsActor;

  @Inject
  @Named("location_actor")
  private ActorRef locationActor;

  @Inject
  @Named("org_management_actor")
  private ActorRef organisationManagementActor;

  @Override
  public void onReceive(Request request) throws Throwable {
    String operation = request.getOperation();
    Util.initializeContext(request, TelemetryEnvKey.ORGANISATION);
    if (operation.equalsIgnoreCase("orgBulkUploadBackground")) {
      handleBulkUploadBackground(
          request,
          (baseBulkUpload) -> {
            processBulkUpload(
                (BulkUploadProcess) baseBulkUpload,
                (tasks) -> {
                  processTasks((List<BulkUploadProcessTask>) tasks, request.getRequestContext());
                  return null;
                },
                request.getRequestContext());
            return null;
          });
    } else {
      onReceiveUnsupportedOperation();
    }
  }

  private void processTasks(
      List<BulkUploadProcessTask> bulkUploadProcessTasks, RequestContext context) {
    Map<String, Location> locationCache = new HashMap<>();
    LocationClient locationClient = new LocationClientImpl();
    for (BulkUploadProcessTask task : bulkUploadProcessTasks) {
      if (task.getStatus() != null
          && task.getStatus() != ProjectUtil.BulkProcessStatus.COMPLETED.getValue()) {
        processOrg(task, locationClient, locationCache, locationActor, context);
        task.setLastUpdatedOn(new Timestamp(System.currentTimeMillis()));
        task.setIterationId(task.getIterationId() + 1);
      }
    }
  }

  private void processOrg(
      BulkUploadProcessTask task,
      LocationClient locationClient,
      Map<String, Location> locationCache,
      ActorRef locationActor,
      RequestContext context) {
    logger.info(context, "OrgBulkUploadBackgroundJobActor: processOrg called");
    String data = task.getData();
    ObjectMapper mapper = new ObjectMapper();
    try {
      Map<String, Object> orgMap = mapper.readValue(data, Map.class);
      Object mandatoryColumnsObject =
          systemSettingClient.getSystemSettingByFieldAndKey(
              systemSettingsActor,
              "orgProfileConfig",
              "csv.mandatoryColumns",
              new TypeReference<String[]>() {},
              context);
      if (mandatoryColumnsObject != null) {
        validateMandatoryFields(orgMap, task, (String[]) mandatoryColumnsObject);
      }
      int status = getOrgStatus(orgMap);
      if (status == -1) {
        orgMap.put(JsonKey.ERROR_MSG, ResponseCode.invalidOrgStatus.getErrorMessage());
        task.setFailureResult(mapper.writeValueAsString(orgMap));
        task.setStatus(ProjectUtil.BulkProcessStatus.FAILED.getValue());
        return;
      }

      List<String> locationCodes = new ArrayList<>();
      if (orgMap.get(JsonKey.LOCATION_CODE) instanceof String) {
        locationCodes.add((String) orgMap.get(JsonKey.LOCATION_CODE));
      } else {
        locationCodes = (List<String>) orgMap.get(JsonKey.LOCATION_CODE);
      }

      String organisationType = (String) orgMap.get(JsonKey.ORG_TYPE);
      if (StringUtils.isNotBlank(organisationType)) {
        orgMap.put(JsonKey.ORG_TYPE, OrgTypeEnum.getValueByType(organisationType));
      }
      Organisation organisation = mapper.convertValue(orgMap, Organisation.class);
      organisation.setStatus(status);
      organisation.setId((String) orgMap.get(JsonKey.ORGANISATION_ID));

      if (StringUtils.isEmpty(organisation.getId())) {
        callCreateOrg(organisation, task, locationCodes, context);
      } else {
        callUpdateOrg(organisation, task, locationCodes, context);
      }
      setLocationInformation(
          task, locationClient, locationCache, locationActor, locationCodes, context);
    } catch (Exception e) {
      logger.error(
          context,
          "OrgBulkUploadBackgroundJobActor:callCreateOrg: Exception occurred with error message = "
              + e.getMessage(),
          e);
    }
  }

  private void setLocationInformation(
      BulkUploadProcessTask task,
      LocationClient locationClient,
      Map<String, Location> locationCache,
      ActorRef locationActor,
      List<String> locationCodes,
      RequestContext context)
      throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    if (ProjectUtil.BulkProcessStatus.COMPLETED.getValue() == task.getStatus()) {
      List<String> locationNames = new ArrayList<>();
      for (String locationCode : locationCodes) {
        if (locationCache.containsKey(locationCode)) {
          locationNames.add(locationCache.get(locationCode).getName());
        } else {
          Location location =
              locationClient.getLocationByCode(locationActor, locationCode, context);
          locationNames.add(location.getName());
        }
      }
      Map<String, Object> row = mapper.readValue(task.getSuccessResult(), Map.class);
      if (locationNames.size() == 1) {
        row.put(JsonKey.LOCATION_NAME, locationNames.get(0));
        row.put(JsonKey.LOCATION_CODE, locationCodes.get(0));
      } else {
        row.put(JsonKey.LOCATION_NAME, locationNames);
        row.put(JsonKey.LOCATION_CODE, locationCodes);
      }
      task.setSuccessResult(mapper.writeValueAsString(row));
    }
  }

  private int getOrgStatus(Map<String, Object> orgMap) {
    int status = ProjectUtil.OrgStatus.ACTIVE.getValue();
    if (!StringUtils.isEmpty((String) orgMap.get(JsonKey.STATUS))) {
      if (((String) orgMap.get(JsonKey.STATUS)).equalsIgnoreCase(JsonKey.INACTIVE)) {
        status = ProjectUtil.OrgStatus.INACTIVE.getValue();
      } else if (((String) orgMap.get(JsonKey.STATUS)).equalsIgnoreCase(JsonKey.ACTIVE)) {
        status = ProjectUtil.OrgStatus.ACTIVE.getValue();
      } else {
        return -1;
      }
      orgMap.remove(JsonKey.STATUS);
    }
    return status;
  }

  private void callCreateOrg(
      Organisation org,
      BulkUploadProcessTask task,
      List<String> locationCodes,
      RequestContext context)
      throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> row = mapper.convertValue(org, Map.class);
    row.put(JsonKey.LOCATION_CODE, locationCodes);
    String orgId;
    row.put(JsonKey.ORG_TYPE, OrgTypeEnum.getTypeByValue(org.getOrganisationType()));
    try {
      orgId = orgClient.createOrg(organisationManagementActor, row, context);
    } catch (Exception ex) {
      logger.error(
          context,
          "OrgBulkUploadBackgroundJobActor:callCreateOrg: Exception occurred with error message = "
              + ex.getMessage(),
          ex);
      setTaskStatus(
          task, ProjectUtil.BulkProcessStatus.FAILED, ex.getMessage(), row, JsonKey.CREATE);
      return;
    }

    if (StringUtils.isEmpty(orgId)) {
      logger.info(context, "OrgBulkUploadBackgroundJobActor:callCreateOrg: Org ID is null !");
      setTaskStatus(
          task,
          ProjectUtil.BulkProcessStatus.FAILED,
          ResponseCode.internalError.getErrorMessage(),
          row,
          JsonKey.CREATE);
    } else {
      row.put(JsonKey.ORGANISATION_ID, orgId);
      setSuccessTaskStatus(task, ProjectUtil.BulkProcessStatus.COMPLETED, row, JsonKey.CREATE);
    }
  }

  private void callUpdateOrg(
      Organisation org,
      BulkUploadProcessTask task,
      List<String> locationCodes,
      RequestContext context)
      throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> row = mapper.convertValue(org, Map.class);
    row.put(JsonKey.LOCATION_CODE, locationCodes);
    row.put(JsonKey.ORG_TYPE, OrgTypeEnum.getTypeByValue(org.getOrganisationType()));
    try {
      row.put(JsonKey.ORGANISATION_ID, org.getId());
      orgClient.updateOrg(organisationManagementActor, row, context);
    } catch (Exception ex) {
      logger.error(
          context,
          "OrgBulkUploadBackgroundJobActor:callUpdateOrg: Exception occurred with error message = "
              + ex.getMessage(),
          ex);
      row.put(JsonKey.ERROR_MSG, ex.getMessage());
      setTaskStatus(
          task, ProjectUtil.BulkProcessStatus.FAILED, ex.getMessage(), row, JsonKey.UPDATE);
    }
    if (task.getStatus() != ProjectUtil.BulkProcessStatus.FAILED.getValue()) {
      task.setData(mapper.writeValueAsString(row));
      setSuccessTaskStatus(task, ProjectUtil.BulkProcessStatus.COMPLETED, row, JsonKey.UPDATE);
    }
  }

  @Override
  public void preProcessResult(Map<String, Object> result) {
    // Do nothing
  }
}
