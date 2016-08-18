package org.zstack.core.scheduler;

import org.springframework.beans.factory.annotation.Autowired;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.header.apimediator.ApiMessageInterceptionException;
import org.zstack.header.apimediator.ApiMessageInterceptor;
import org.zstack.header.apimediator.StopRoutingException;
import org.zstack.header.core.scheduler.APICreateSchedulerMessage;
import org.zstack.header.core.scheduler.SchedulerVO;
import org.zstack.header.errorcode.SysErrors;
import org.zstack.header.message.APIMessage;

import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;

/**
 * Created by Mei Lei on 7/5/16.
 */
public class SchedulerApiInterceptor implements ApiMessageInterceptor {
    @Autowired
    private CloudBus bus;
    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private ErrorFacade errf;

    private void setServiceId(APIMessage msg) {
        if (msg instanceof SchedulerMessage) {
            SchedulerMessage schedmsg = (SchedulerMessage) msg;
            bus.makeTargetServiceIdByResourceUuid(msg, SchedulerConstant.SERVICE_ID, schedmsg.getSchedulerUuid());
        }
    }
    // meilei: to do strict check for api
    @Override
    public APIMessage intercept(APIMessage msg) throws ApiMessageInterceptionException {
        setServiceId(msg);
        if (msg instanceof APIDeleteSchedulerMsg) {
            validate((APIDeleteSchedulerMsg) msg);
        } else if (msg instanceof APIUpdateSchedulerMsg) {
            validate((APIUpdateSchedulerMsg) msg);
        } else if (msg instanceof APICreateSchedulerMessage ) {
            validate((APICreateSchedulerMessage) msg);
        }
        return msg;
    }

    private void validate(APIDeleteSchedulerMsg msg) {
        if (!dbf.isExist(msg.getUuid(), SchedulerVO.class)) {
            APIDeleteSchedulerEvent evt = new APIDeleteSchedulerEvent(msg.getId());
            bus.publish(evt);
            throw new StopRoutingException();
        }
    }

    private void validate(APIUpdateSchedulerMsg msg) {
        if (!dbf.isExist(msg.getUuid(), SchedulerVO.class)) {
            APIDeleteSchedulerEvent evt = new APIDeleteSchedulerEvent(msg.getId());
            bus.publish(evt);
            throw new StopRoutingException();
        }
    }

    private void validate(APICreateSchedulerMessage msg) {
        if (msg.getStartDate() != null && msg.getStartDate() < 0) {
            throw new ApiMessageInterceptionException(errf.instantiateErrorCode(SysErrors.INVALID_ARGUMENT_ERROR,
                    String.format("startDate must be positive integer or 0")
            ));
        } else if (msg.getStartDate() > 0 ){
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
                Timestamp ts = new Timestamp(msg.getStartDate());
                sdf.parse(ts.toString());
            } catch (ParseException e) {
                throw new ApiMessageInterceptionException(errf.instantiateErrorCode(SysErrors.INVALID_ARGUMENT_ERROR,
                        String.format("startDate is not a valid timestamp")
                ));
            }
        }

        if (msg.getRepeatCount() != null && msg.getRepeatCount() <= 0) {
            throw new ApiMessageInterceptionException(errf.instantiateErrorCode(SysErrors.INVALID_ARGUMENT_ERROR,
                    String.format("repeatCount must be positive integer")
            ));
        }

        if (msg.getType().equals("simple")) {
            if (msg.getInterval() == null) {
                throw new ApiMessageInterceptionException(errf.instantiateErrorCode(SysErrors.INVALID_ARGUMENT_ERROR,
                        String.format("interval and startDate must be set when use simple scheduler")
                ));
            }
            if (msg.getStartDate() == null) {
                throw new ApiMessageInterceptionException(errf.instantiateErrorCode(SysErrors.INVALID_ARGUMENT_ERROR,
                        String.format("interval and startDate must be set when use simple scheduler")
                ));
            }
        }

        if (msg.getType().equals("cron")) {
            if (msg.getCron() == null || msg.getCron().isEmpty()) {
                throw new ApiMessageInterceptionException(errf.instantiateErrorCode(SysErrors.INVALID_ARGUMENT_ERROR,
                        String.format("cron must be set when use cron scheduler")
                ));
            }
            if ( ! msg.getCron().contains("?") || msg.getCron().split(" ").length != 6) {
                throw new ApiMessageInterceptionException(errf.instantiateErrorCode(SysErrors.INVALID_ARGUMENT_ERROR,
                        String.format("cron task must follow format like this : \"0 0/3 17-23 * * ?\" ")
                ));

            }
        }
    }

}
