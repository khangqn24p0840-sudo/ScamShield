package vn.scamshield
import android.telecom.Call
import android.telecom.CallScreeningService

class ScamCallService : CallScreeningService() {
    override fun onScreenCall(d: Call.Details) {
        val block = CallGuard.handle(this, d.handle?.schemeSpecificPart ?: "")
        respondToCall(d, if (block) CallResponse.Builder().setDisallowCall(true).setRejectCall(true).setSkipNotification(true).build()
                          else CallResponse.Builder().build())
    }
}
