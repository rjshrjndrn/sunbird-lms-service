package org.sunbird.actor.bulkupload;

import akka.actor.ActorRef;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import org.sunbird.client.systemsettings.SystemSettingClient;
import org.sunbird.client.systemsettings.impl.SystemSettingClientImpl;
import org.sunbird.common.ElasticSearchHelper;
import org.sunbird.common.factory.EsClientFactory;
import org.sunbird.common.inf.ElasticSearchService;
import org.sunbird.dao.bulkupload.BulkUploadProcessDao;
import org.sunbird.dao.bulkupload.impl.BulkUploadProcessDaoImpl;
import org.sunbird.exception.ProjectCommonException;
import org.sunbird.exception.ResponseCode;
import org.sunbird.keys.JsonKey;
import org.sunbird.model.bulkupload.BulkUploadProcess;
import org.sunbird.operations.BulkUploadActorOperation;
import org.sunbird.request.Request;
import org.sunbird.request.RequestContext;
import org.sunbird.telemetry.dto.TelemetryEnvKey;
import org.sunbird.util.DataCacheHandler;
import org.sunbird.util.ProjectUtil;
import org.sunbird.util.Util;
import scala.concurrent.Future;

public class OrgBulkUploadActor extends BaseBulkUploadActor {

  private SystemSettingClient systemSettingClient = new SystemSettingClientImpl();
  private ElasticSearchService esService = EsClientFactory.getInstance(JsonKey.REST);

  @Inject
  @Named("org_bulk_upload_background_job_actor")
  private ActorRef orgBulkUploadBackgroundJobActor;

  @Inject
  @Named("system_settings_actor")
  private ActorRef systemSettingsActor;

  @Override
  public void onReceive(Request request) throws Throwable {
    Util.initializeContext(request, TelemetryEnvKey.ORGANISATION);
    String operation = request.getOperation();

    if (operation.equalsIgnoreCase("orgBulkUpload")) {
      upload(request);
    } else {
      onReceiveUnsupportedOperation();
    }
  }

  private void upload(Request request) throws IOException {
    Map<String, Object> req = (Map<String, Object>) request.getRequest().get(JsonKey.DATA);
    Object dataObject =
        systemSettingClient.getSystemSettingByFieldAndKey(
            systemSettingsActor,
            "orgProfileConfig",
            "csv",
            new TypeReference<Map>() {},
            request.getRequestContext());
    Map<String, Object> supportedColumnsMap = null;
    Map<String, Object> supportedColumnsLowerCaseMap = null;
    if (dataObject != null) {
      supportedColumnsMap =
          ((Map<String, Object>) ((Map<String, Object>) dataObject).get("supportedColumns"));
      List<String> supportedColumnsList = new ArrayList<>();
      supportedColumnsLowerCaseMap =
          supportedColumnsMap
              .entrySet()
              .stream()
              .collect(
                  Collectors.toMap(
                      entry -> (entry.getKey()).toLowerCase(), entry -> entry.getValue()));
      Map<String, Object> internalNamesLowerCaseMap = new HashMap<>();
      supportedColumnsMap.forEach(
          (String k, Object v) -> {
            internalNamesLowerCaseMap.put(v.toString().toLowerCase(), v.toString());
          });
      supportedColumnsLowerCaseMap.putAll(internalNamesLowerCaseMap);
      supportedColumnsLowerCaseMap.forEach(
          (key, value) -> {
            supportedColumnsList.add(key);
            supportedColumnsList.add((String) value);
          });
      List<String> mandatoryColumns =
          (List<String>) (((Map<String, Object>) dataObject).get("mandatoryColumns"));
      validateFileHeaderFields(
          req,
          supportedColumnsList.toArray(new String[supportedColumnsList.size()]),
          false,
          true,
          mandatoryColumns,
          supportedColumnsLowerCaseMap);
    } else {
      validateFileHeaderFields(req, DataCacheHandler.bulkOrgAllowedFields, false, false);
    }
    BulkUploadProcess bulkUploadProcess =
        handleUpload(
            JsonKey.ORGANISATION,
            (String) req.get(JsonKey.CREATED_BY),
            request.getRequestContext());
    processOrgBulkUpload(
        req,
        bulkUploadProcess.getId(),
        bulkUploadProcess,
        supportedColumnsLowerCaseMap,
        request.getRequestContext());
  }

  private void processOrgBulkUpload(
      Map<String, Object> req,
      String processId,
      BulkUploadProcess bulkUploadProcess,
      Map<String, Object> supportedColumnsMap,
      RequestContext context)
      throws IOException {
    byte[] fileByteArray = null;
    if (null != req.get(JsonKey.FILE)) {
      fileByteArray = (byte[]) req.get(JsonKey.FILE);
    }
    HashMap<String, Object> additionalInfo = new HashMap<>();
    Map<String, Object> user = getUser((String) req.get(JsonKey.CREATED_BY), context);
    if (user != null) {
      String rootOrgId = (String) user.get(JsonKey.ROOT_ORG_ID);
      Map<String, Object> org = getOrg(rootOrgId, context);
      if (org != null) {
        if (org.get(JsonKey.STATUS) == null
            || (int) org.get(JsonKey.STATUS) == ProjectUtil.OrgStatus.ACTIVE.getValue()) {
          additionalInfo.put(JsonKey.CHANNEL, org.get(JsonKey.CHANNEL));
        }
      }
    }
    if (!additionalInfo.containsKey(JsonKey.CHANNEL)) {
      bulkUploadProcess.setStatus(ProjectUtil.BulkProcessStatus.FAILED.getValue());
      bulkUploadProcess.setFailureResult(ResponseCode.errorNoRootOrgAssociated.getErrorMessage());
      BulkUploadProcessDao bulkUploadDao = new BulkUploadProcessDaoImpl();
      bulkUploadDao.update(bulkUploadProcess, context);
      ProjectCommonException.throwClientErrorException(
          ResponseCode.errorNoRootOrgAssociated,
          ResponseCode.errorNoRootOrgAssociated.getErrorMessage());
    }
    Integer recordCount =
        validateAndParseRecords(
            fileByteArray, processId, additionalInfo, supportedColumnsMap, true, context);
    processBulkUpload(
        orgBulkUploadBackgroundJobActor,
        recordCount,
        processId,
        bulkUploadProcess,
        BulkUploadActorOperation.ORG_BULK_UPLOAD_BACKGROUND_JOB.getValue(),
        DataCacheHandler.bulkOrgAllowedFields,
        context);
  }

  Map<String, Object> getUser(String userId, RequestContext context) {
    Future<Map<String, Object>> resultF =
        esService.getDataByIdentifier(ProjectUtil.EsType.user.getTypeName(), userId, context);
    Map<String, Object> result =
        (Map<String, Object>) ElasticSearchHelper.getResponseFromFuture(resultF);
    if (result != null || result.size() > 0) {
      return result;
    }
    return null;
  }

  Map<String, Object> getOrg(String orgId, RequestContext context) {
    Future<Map<String, Object>> resultF =
        esService.getDataByIdentifier(
            ProjectUtil.EsType.organisation.getTypeName(), orgId, context);
    Map<String, Object> result =
        (Map<String, Object>) ElasticSearchHelper.getResponseFromFuture(resultF);
    if (result != null && result.size() > 0) {
      return result;
    }
    return null;
  }
}
