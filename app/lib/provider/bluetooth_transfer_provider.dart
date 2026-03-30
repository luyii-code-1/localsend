import 'dart:io';
import 'dart:typed_data';
import 'dart:convert';

import 'package:common/model/device.dart';
import 'package:flutter/material.dart';
import 'package:localsend_app/model/cross_file.dart';
import 'package:localsend_app/provider/network/nearby_devices_provider.dart';
import 'package:localsend_app/provider/network/scan_facade.dart';
import 'package:localsend_app/provider/network/send_provider.dart';
import 'package:localsend_app/util/native/channel/android_channel.dart';
import 'package:localsend_app/util/native/platform_check.dart';
import 'package:logging/logging.dart';
import 'package:refena_flutter/refena_flutter.dart';

final _logger = Logger('BluetoothTransfer');

const bluetoothDirectTransferThreshold = 8 * 1024 * 1024;
const hotspotControlPacketName = '__localsend_hotspot__.json';

class SendFilesToBluetoothDeviceAction extends AsyncGlobalActionWithResult<bool> {
  final String address;
  final String? targetAlias;
  final List<CrossFile> files;

  SendFilesToBluetoothDeviceAction({
    required this.address,
    required this.targetAlias,
    required this.files,
  });

  @override
  Future<bool> reduce() async {
    if (!checkPlatform([TargetPlatform.android])) {
      return false;
    }

    final totalSize = files.fold<int>(0, (sum, file) => sum + file.size);
    if (totalSize > bluetoothDirectTransferThreshold) {
      final hotspot = await startLocalOnlyHotspotAndroid();
      if (hotspot == null) {
        _logger.warning('Failed to create local-only hotspot for large file transfer');
        return false;
      }
      final payload = utf8.encode(jsonEncode(hotspot));
      final controlSent = await sendBluetoothFileAndroid(
        address: address,
        fileName: hotspotControlPacketName,
        data: Uint8List.fromList(payload),
      );
      if (!controlSent) {
        await stopLocalOnlyHotspotAndroid();
        _logger.warning('Failed to send hotspot control packet over Bluetooth');
      }
      if (!controlSent) {
        return false;
      }

      // Reuse original LAN discovery + transfer stack after receiver connects to AP.
      try {
        List<Device> devices = [];
        for (int i = 0; i < 4; i++) {
          await Future<void>.delayed(const Duration(seconds: 3));
          await ref.global.dispatchAsync(StartSmartScan(forceLegacy: true));
          devices = ref.read(nearbyDevicesProvider).allDevices.values.toList();
          if (devices.isNotEmpty) {
            break;
          }
        }
        if (devices.isEmpty) {
          _logger.warning('No LAN device discovered after hotspot setup');
          return false;
        }
        final target = devices.firstWhere(
          (d) => targetAlias != null && d.alias == targetAlias,
          orElse: () => devices.first,
        );

        await ref.notifier(sendProvider).startSession(
              target: target,
              files: files,
              background: false,
            );
        return true;
      } catch (e, st) {
        _logger.warning('Failed to start LAN transfer after AP setup', e, st);
        return false;
      }
    }

    for (final file in files) {
      if (file.bytes == null && file.path == null) {
        _logger.warning('File has neither bytes nor path: ${file.name}');
        return false;
      }
      final bytes = file.bytes != null ? Uint8List.fromList(file.bytes!) : await File(file.path!).readAsBytes();
      final success = await sendBluetoothFileAndroid(
        address: address,
        fileName: file.name,
        data: bytes,
      );
      if (!success) {
        _logger.warning('Failed to send file via Bluetooth: ${file.name}');
        return false;
      }
    }

    return true;
  }
}
