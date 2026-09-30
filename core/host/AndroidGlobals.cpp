// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * The Android globals of src/Android/Main.cpp (link-time seam, see
 * mobile/docs/DECISIONS.md D5).  That file also holds the JNI entry
 * points of the old NativeView UI, so the core defines these itself;
 * the new app's JNI glue (M2) creates the objects before xcs_start().
 */

#include "Android/Main.hpp"

Context *context;
NativeView *native_view;
Vibrator *vibrator;
BluetoothHelper *bluetooth_helper;
UsbSerialHelper *usb_serial_helper;
IOIOHelper *ioio_helper;
SAFHelper *saf_helper;
