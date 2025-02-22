package com.github.scphamster.bluetoothConnectionsTester.device

import android.content.Context
import android.util.Log
import com.github.scphamster.bluetoothConnectionsTester.R
import com.github.scphamster.bluetoothConnectionsTester.dataLink.BluetoothBridge

class Commander(val dataLink: BluetoothBridge, val context: Context) {
    fun sendCommand(cmd: ControllerResponseInterpreter.Commands.SetVoltageAtPin) {
        val command: String
        val command_argument: String

        command = context.getString(R.string.set_pin_cmd)
        command_argument = cmd.pin.toString()

        dataLink.sendRawCommand(command + " " + command_argument)
    }

    fun sendCommand(cmd: ControllerResponseInterpreter.Commands.SetOutputVoltageLevel) {
        val command: String
        val command_argument: String

        command = context.getString(R.string.set_output_voltage)
        command_argument = cmd.level.text

        dataLink.sendRawCommand(command + ' ' + command_argument)
    }

    fun sendCommand(cmd: ControllerResponseInterpreter.Commands.CheckConnectivity) {
        val command: String
        val command_argument = if (cmd.pinAffinityAndId == null) {
            String()
        }
        else {
            "${cmd.pinAffinityAndId.boardAddress} ${cmd.pinAffinityAndId.pinID}"
        }

        val if_sequential = if (cmd.sequential) "sequential"
        else ""

        val composed_command = (cmd.base + ' ' + cmd.domain.text + ' ' + command_argument + ' ' + if_sequential).trim()
        Log.d("Commander", composed_command)
        dataLink.sendRawCommand(composed_command)
    }

    fun sendCommand(cmd: ControllerResponseInterpreter.Commands.CheckHardware) {
        //todo: make supervisor to watch for answer to happen, if answer will not be obtained - controller is unhealthy
        dataLink.sendRawCommand(context.getString(R.string.get_all_boads_online))
    }

    fun sendCommand(cmd: ControllerResponseInterpreter.Commands.GetInternalParameters){
        dataLink.sendRawCommand("getinternals ${cmd.board_addr}")
    }
}