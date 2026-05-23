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
//
// The L2CAP `l2c_fcr_hook` offsets stay stable across monthly OS drops on a given
// SDK level, so we trust Pixel + SDK ≥ 36 wholesale rather than maintaining a
// hand-curated allowlist of monthly Build.ID strings (which goes stale every month).
// If a future build breaks the hook, the Xposed module itself will fail to load
// and `XposedState.bluetoothScopeEnabled` stays false — the UI then degrades
// cleanly. Same heuristic as OnePlus/Oppo.
fun isAacpCapable(): Boolean {
    val mfr = Build.MANUFACTURER.lowercase()
    val sdk = Build.VERSION.SDK_INT
    return when {
        mfr == "google" -> sdk >= 36
        mfr in listOf("oneplus", "oppo") -> sdk >= 36
        else -> false
    }
}
