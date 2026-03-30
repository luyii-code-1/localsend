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

final bluetoothBroadcastOnProvider = StateProvider<bool>((ref) {
  return false;
}, debugLabel: 'bluetoothBroadcastOnProvider');

class StartBluetoothDiscoveryAction extends AsyncGlobalAction {
  @override
  Future<void> reduce() async {
    if (!checkPlatform([TargetPlatform.android])) {
      return;
    }

    final permissionGranted = await requestBluetoothPermissionsAndroid();
    if (!permissionGranted) {
      ref.notifier(bluetoothBroadcastOnProvider).setState((_) => false);
      return;
    }

    final broadcastOn = await startBluetoothFileServerAndroid();
    ref.notifier(bluetoothBroadcastOnProvider).setState((_) => broadcastOn);

    final devices = await scanBluetoothDevicesAndroid();
    ref.notifier(bluetoothDiscoveryProvider).setState((_) => devices);

    final signalInfo = await getBluetoothSignalInfoAndroid();
    ref.notifier(bluetoothSignalInfoProvider).setState((_) => signalInfo);
  }
}
