import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:localsend_app/model/cross_file.dart';
import 'package:localsend_app/util/native/channel/android_channel.dart';
import 'package:localsend_app/util/native/platform_check.dart';
import 'package:logging/logging.dart';
import 'package:refena_flutter/refena_flutter.dart';

final _logger = Logger('BluetoothTransfer');

const bluetoothDirectTransferThreshold = 8 * 1024 * 1024;

class SendFilesToBluetoothDeviceAction extends AsyncGlobalAction<bool> {
  final String address;
  final List<CrossFile> files;

  SendFilesToBluetoothDeviceAction({
    required this.address,
    required this.files,
  });

  @override
  Future<bool> reduce() async {
    if (!checkPlatform([TargetPlatform.android])) {
      return false;
    }

    final totalSize = files.fold<int>(0, (sum, file) => sum + file.size);
    if (totalSize > bluetoothDirectTransferThreshold) {
      _logger.warning('Direct Bluetooth transfer skipped due to size limit: $totalSize bytes');
      return false;
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
