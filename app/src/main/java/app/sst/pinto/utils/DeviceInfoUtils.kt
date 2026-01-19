package app.sst.pinto.utils

import android.os.Build
import java.net.NetworkInterface

fun getDeviceIpAddress(): String {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address.hostAddress != null) {
                    val hostAddress = address.hostAddress
                    if (hostAddress?.contains(':') == false) {
                        return hostAddress
                    }
                }
            }
        }
        "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}

fun getDeviceSerialNumber(): String {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Build.getSerial()
        } else {
            @Suppress("DEPRECATION")
            Build.SERIAL
        }
    } catch (e: Exception) {
        "unknown"
    }
}






