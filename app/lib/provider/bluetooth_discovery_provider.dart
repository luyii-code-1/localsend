import 'package:flutter/material.dart';
import 'package:localsend_app/util/native/channel/android_channel.dart';
import 'package:localsend_app/util/native/platform_check.dart';
import 'package:refena_flutter/refena_flutter.dart';

final bluetoothDiscoveryProvider = StateProvider<List<BluetoothDeviceInfo>>((ref) {
  return [];
}, debugLabel: 'bluetoothDiscoveryProvider');

final bluetoothSignalInfoProvider = StateProvider<Map<String, String>>((ref) {
  return {};
}, debugLabel: 'bluetoothSignalInfoProvider');

class StartBluetoothDiscoveryAction extends AsyncGlobalAction {
  @override
  Future<void> reduce() async {
    if (!checkPlatform([TargetPlatform.android])) {
      return;
    }

    final devices = await scanBluetoothDevicesAndroid();
    ref.notifier(bluetoothDiscoveryProvider).setState(devices);

    final signalInfo = await getBluetoothSignalInfoAndroid();
    ref.notifier(bluetoothSignalInfoProvider).setState(signalInfo);
  }
}
