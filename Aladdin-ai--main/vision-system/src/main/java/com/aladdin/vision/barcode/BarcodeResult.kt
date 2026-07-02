package com.aladdin.vision.barcode

import android.graphics.Rect
import com.google.mlkit.vision.barcode.common.Barcode

data class BarcodeResult(
    val rawValue: String,
    val format: String,
    val valueType: BarcodeValueType,
    val boundingBox: Rect?,
    val url: String?,
    val contactInfo: ContactInfo?,
    val calendarEvent: CalendarEventInfo?,
    val wifiInfo: WifiInfo?,
    val phone: String?,
    val email: String?,
    val sms: SmsInfo?,
    val geoPoint: GeoPoint?
) {
    companion object {
        fun from(barcode: Barcode): BarcodeResult {
            val valueType = when (barcode.valueType) {
                Barcode.TYPE_URL -> BarcodeValueType.URL
                Barcode.TYPE_CONTACT_INFO -> BarcodeValueType.CONTACT
                Barcode.TYPE_CALENDAR_EVENT -> BarcodeValueType.CALENDAR
                Barcode.TYPE_WIFI -> BarcodeValueType.WIFI
                Barcode.TYPE_PHONE -> BarcodeValueType.PHONE
                Barcode.TYPE_EMAIL -> BarcodeValueType.EMAIL
                Barcode.TYPE_SMS -> BarcodeValueType.SMS
                Barcode.TYPE_GEO -> BarcodeValueType.GEO
                Barcode.TYPE_TEXT -> BarcodeValueType.TEXT
                Barcode.TYPE_ISBN -> BarcodeValueType.ISBN
                Barcode.TYPE_PRODUCT -> BarcodeValueType.PRODUCT
                else -> BarcodeValueType.UNKNOWN
            }

            val format = when (barcode.format) {
                Barcode.FORMAT_QR_CODE -> "QR_CODE"
                Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
                Barcode.FORMAT_PDF417 -> "PDF417"
                Barcode.FORMAT_AZTEC -> "AZTEC"
                Barcode.FORMAT_EAN_13 -> "EAN_13"
                Barcode.FORMAT_EAN_8 -> "EAN_8"
                Barcode.FORMAT_UPC_A -> "UPC_A"
                Barcode.FORMAT_UPC_E -> "UPC_E"
                Barcode.FORMAT_CODE_39 -> "CODE_39"
                Barcode.FORMAT_CODE_93 -> "CODE_93"
                Barcode.FORMAT_CODE_128 -> "CODE_128"
                else -> "UNKNOWN"
            }

            val contact = barcode.contactInfo?.let { c ->
                ContactInfo(
                    name = c.name?.formattedName ?: "",
                    phones = c.phones.map { it.number ?: "" },
                    emails = c.emails.map { it.address ?: "" },
                    organization = c.organization ?: "",
                    title = c.title ?: "",
                    urls = c.urls.toList()
                )
            }

            val calendar = barcode.calendarEvent?.let { ev ->
                CalendarEventInfo(
                    summary = ev.summary ?: "",
                    description = ev.description ?: "",
                    location = ev.location ?: "",
                    start = ev.start?.rawValue ?: "",
                    end = ev.end?.rawValue ?: ""
                )
            }

            val wifi = barcode.wifi?.let { w ->
                WifiInfo(
                    ssid = w.ssid ?: "",
                    password = w.password ?: "",
                    encryptionType = when (w.encryptionType) {
                        Barcode.WiFi.TYPE_WPA -> "WPA"
                        Barcode.WiFi.TYPE_WEP -> "WEP"
                        else -> "OPEN"
                    }
                )
            }

            val geo = barcode.geoPoint?.let { g ->
                GeoPoint(latitude = g.lat, longitude = g.lng)
            }

            val sms = barcode.sms?.let { s ->
                SmsInfo(phoneNumber = s.phoneNumber ?: "", message = s.message ?: "")
            }

            return BarcodeResult(
                rawValue = barcode.rawValue ?: "",
                format = format,
                valueType = valueType,
                boundingBox = barcode.boundingBox,
                url = barcode.url?.url,
                contactInfo = contact,
                calendarEvent = calendar,
                wifiInfo = wifi,
                phone = barcode.phone?.number,
                email = barcode.email?.address,
                sms = sms,
                geoPoint = geo
            )
        }
    }
}

enum class BarcodeValueType {
    URL, CONTACT, CALENDAR, WIFI, PHONE, EMAIL, SMS, GEO, TEXT, ISBN, PRODUCT, UNKNOWN
}

data class ContactInfo(
    val name: String,
    val phones: List<String>,
    val emails: List<String>,
    val organization: String,
    val title: String,
    val urls: List<String>
)

data class CalendarEventInfo(
    val summary: String,
    val description: String,
    val location: String,
    val start: String,
    val end: String
)

data class WifiInfo(
    val ssid: String,
    val password: String,
    val encryptionType: String
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

data class SmsInfo(
    val phoneNumber: String,
    val message: String
)
