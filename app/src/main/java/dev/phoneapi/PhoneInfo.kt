package dev.phoneapi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections

object PhoneInfo {
    fun addresses(): JSONArray {
        val result = JSONArray()
        for (network in Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp) continue
            for (address in Collections.list(network.inetAddresses)) {
                if (address.isLinkLocalAddress) continue
                val ip = address.hostAddress ?: continue
                val octets = ip.split('.')
                val tailscale = (octets.size == 4 && octets[0] == "100" && (octets[1].toIntOrNull() ?: 0) in 64..127)
                    || ip.startsWith("fd7a:115c:a1e0:", ignoreCase = true)
                result.put(JSONObject().put("interface", network.name).put("address", ip)
                    .put("kind", if (tailscale) "tailscale" else if (address.isLoopbackAddress) "loopback" else "network"))
            }
        }
        return result
    }

    fun subscriptions(context: Context): JSONArray {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) throw SecurityException()
        val result = JSONArray()
        context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().forEach {
            result.put(JSONObject().put("subscriptionId", it.subscriptionId).put("name", it.displayName.toString())
                .put("carrier", it.carrierName.toString()).put("slot", it.simSlotIndex)
                .put("embedded", it.isEmbedded).put("defaultSms", it.subscriptionId == SubscriptionManager.getDefaultSmsSubscriptionId()))
        }
        return result
    }
}
