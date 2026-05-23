/*
    ProPods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 ProPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package io.nikos.propods.utils

import android.os.Build

// Returns true when the device can in principle open the AACP L2CAP socket to the
// AirPods. False means the device runs the same UI but every AACP-controlled
// feature is greyed out (battery, handover, BLE-based features still work).
fun isAacpCapable(): Boolean {
    val isPixel = Build.MANUFACTURER.lowercase() == "google"
    val isOppoOrOnePlus = Build.MANUFACTURER.lowercase() in listOf("oneplus", "oppo")

    if (isPixel) {
        when (Build.VERSION.SDK_INT) {
            36 -> return Build.ID == "CP1A.260305.018" || Build.ID == "CP1A.260405.005"
            37 -> return true
        }
    } else if (isOppoOrOnePlus) {
        return Build.VERSION.SDK_INT >= 36
    }
    return false
}
