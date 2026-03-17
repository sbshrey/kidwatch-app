package com.kidwatch.app

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * CaptureActivity that stays in portrait orientation.
 * Used for Scan QR to avoid landscape display.
 */
class PortraitCaptureActivity : CaptureActivity()
