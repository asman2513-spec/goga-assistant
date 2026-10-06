package app.goga.tasks

import app.goga.model.DeliveryAck
import app.goga.model.ReminderChannel
import app.goga.model.ReminderChannelIds
import app.goga.model.ReminderDelivery
import app.goga.model.StageNotImplementedException

/** v1 path: AlarmManager on the phone. Wired in stage 2. */
class LocalAlarmReminderChannel : ReminderChannel {
    override val id: String = ReminderChannelIds.LOCAL_ALARM

    override suspend fun deliver(request: ReminderDelivery): DeliveryAck {
        throw StageNotImplementedException("Local alarms are stage 2")
    }
}

/** Push is a supplement for changes and cancellations, not the alarm itself. */
class PushReminderChannel : ReminderChannel {
    override val id: String = ReminderChannelIds.PUSH

    override suspend fun deliver(request: ReminderDelivery): DeliveryAck {
        throw StageNotImplementedException("Push delivery is a later stage")
    }
}

class SmsReminderChannel : ReminderChannel {
    override val id: String = ReminderChannelIds.SMS

    override suspend fun deliver(request: ReminderDelivery): DeliveryAck {
        throw StageNotImplementedException("SMS reminders are not in v1")
    }
}

class CallReminderChannel : ReminderChannel {
    override val id: String = ReminderChannelIds.CALL

    override suspend fun deliver(request: ReminderDelivery): DeliveryAck {
        throw StageNotImplementedException("Call reminders are not in v1")
    }
}
