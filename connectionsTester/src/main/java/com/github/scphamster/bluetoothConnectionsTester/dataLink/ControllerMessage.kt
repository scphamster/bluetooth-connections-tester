package com.github.scphamster.bluetoothConnectionsTester.dataLink

import android.util.Log
import com.github.scphamster.bluetoothConnectionsTester.circuit.*
import com.github.scphamster.bluetoothConnectionsTester.device.IoBoardsManager

sealed class Msg {
    companion object {
        const val Tag = "Msg"
        fun deserialize(byteArray: ByteArray): Msg? {
            val byteIt = byteArray.iterator()
            
            var byteCounter = 0
            
            if (!byteIt.hasNext()) Log.e(Tag, "Zero elements in message byte array!")
            
            var byte = byteIt.nextByte()
            
            val msg = when (byte.toInt()) {
                1 -> {
                    if (byteArray.size - 1 != OperationConfirmation.SIZE_BYTES) {
                        Log.e(Tag,
                              "Message with id: ${byte.toInt()} has improper size: Expected: ${OperationConfirmation.SIZE_BYTES + 1}, Got: ${byteArray.size}")
                    }
                    
                    OperationConfirmation(byteIt.nextByte())
                }
                
                else -> null
            }
            
            return msg
        }
    }
    
    class OperationConfirmation(val confirmationValue: Byte) : Msg() {
        companion object {
            const val ID = 1
            const val SIZE_BYTES = 1
        }
    }
}

interface MasterToControllerMsg {
    abstract fun serialize(): Collection<Byte>
    abstract val msgId: Byte
    
    enum class MessageID(val id: Byte) {
        MeasureAllVoltages(100),
        EnableOutputForPin(101),
        SetOutputVoltageLevel(102),
        CheckConnections(103),
        CheckResistances(104),
        CheckVoltages(105),
        CheckRaw(106),
        GetBoardsOnline(107),
        GetInternalCounter(108),
        GetTaskStackWatermark(109),
        SetNewAddressForBoard(110),
        SetInternalParameters(111),
        GetInternalParameters(112),
        Test(113),
        DataLinkKeepAlive(114),
        DisableOutput(115),
        Dummy(116),
    }
}

class MeasureAllVoltages : MasterToControllerMsg {
    companion object {
        const val COMMAND_SIZE_BYTES = Byte.SIZE_BYTES.toByte()
        val CMD_ID: Byte = MasterToControllerMsg.MessageID.MeasureAllVoltages.id
    }
    
    override val msgId = MasterToControllerMsg.MessageID.MeasureAllVoltages.id
    override fun serialize(): Collection<Byte> {
        val bytes = ArrayList<Byte>()
        bytes.add(CMD_ID)
        return bytes
    }
}

class SetOutputVoltageLevel(val level: IoBoardsManager.VoltageLevel) : MasterToControllerMsg {
    companion object {
        private val CMD_ID = MasterToControllerMsg.MessageID.SetOutputVoltageLevel.id
        private const val SIZE_BYTES = Byte.SIZE_BYTES * 2
        const val RESULT_OBTAINMENT_TIMEOUT_MS = 400.toLong()
    }
    
    override val msgId = MasterToControllerMsg.MessageID.SetOutputVoltageLevel.id
    override fun serialize(): Collection<Byte> {
        val bytes = mutableListOf<Byte>()
        bytes.add(CMD_ID)
        bytes.add(level.byteValue)
        return bytes
    }
}

class GetBoardsOnline(val performRescanOnControllerSide: Boolean = false) : MasterToControllerMsg {
    companion object {
        const val RESULT_TIMEOUT_MS = 4000.toLong()
        const val RESULT_TIMEOUT_WITH_RESCAN_MS = 10_000.toLong()
    }
    
    override val msgId = MasterToControllerMsg.MessageID.GetBoardsOnline.id
    override fun serialize(): Collection<Byte> {
        val performRescan: Byte = if (performRescanOnControllerSide) 1.toByte() else 0.toByte()
        return listOf(msgId, performRescan)
    }
}

class FindConnection(val pin: PinAffinityAndId? = null) : MasterToControllerMsg {
    companion object {
        const val SINGLE_PIN_RESULT_TIMEOUT_MS = 2000.toLong()
        const val MEASURE_ALL_PIN_ID_FILLER = UByte.MAX_VALUE
    }
    
    override fun serialize(): Collection<Byte> {
        
        val pinAsBytes = if (pin != null) {
            listOf(pin.boardAddress.toByte(), pin.pinID.toByte())
        }
        else {
            listOf(MEASURE_ALL_PIN_ID_FILLER.toByte(), MEASURE_ALL_PIN_ID_FILLER.toByte())
        }
        
        return (listOf(msgId) + pinAsBytes)
    }
    
    override val msgId = MasterToControllerMsg.MessageID.CheckConnections.id
}

class KeepAliveMessage : MasterToControllerMsg {
    override val msgId = MasterToControllerMsg.MessageID.DataLinkKeepAlive.id
    
    override fun serialize(): Collection<Byte> {
        return byteArrayOf(msgId).toList()
    }
}

class SetVoltageAtPin(val pin: PinAffinityAndId) : MasterToControllerMsg {
    override val msgId = MasterToControllerMsg.MessageID.EnableOutputForPin.id
    
    override fun serialize(): Collection<Byte> {
        return listOf(msgId, pin.boardAddress.toByte(), pin.pinID.toByte())
    }
}

class DisableOutput : MasterToControllerMsg {
    override val msgId = MasterToControllerMsg.MessageID.DisableOutput.id
    
    override fun serialize(): Collection<Byte> {
        return listOf(msgId)
    }
}

class EchoMessage : MasterToControllerMsg {
    override fun serialize(): Collection<Byte> {
        return listOf(msgId)
    }
    
    override val msgId = MasterToControllerMsg.MessageID.Dummy.id
}

sealed interface MessageFromController {
    enum class Type(val id: Byte) {
        Connections(50),
        OperationConfirmation(51),
        BoardsInfo(52),
        AllBoardsVoltages(53),
        Dummy(54),
        KeepAlive(55)
    }
    
    companion object {
        private const val Tag = "MsgFromController"
        fun deserialize(bytesIterator: Iterator<Byte>): MessageFromController? {
            if (!bytesIterator.hasNext()) {
                Log.e(Tag, "empty byte list!")
                return null
            }
            
            val id = bytesIterator.next()
            
            return when (id) {
                Type.Connections.id -> Connectivity(bytesIterator)
                Type.OperationConfirmation.id -> OperationStatus(bytesIterator)
                Type.BoardsInfo.id -> Boards(bytesIterator)
                Type.AllBoardsVoltages.id -> Voltages(bytesIterator)
                Type.Dummy.id -> Dummy()
                Type.KeepAlive.id -> KeepAlive()
                else -> null
            }
        }
    }
    
    class Connectivity(byteIterator: Iterator<Byte>) : MessageFromController {
        companion object {
            const val SIZE_BYTES = 3
        }
        
        val masterPin: PinAffinityAndId
        val connections: Array<SimpleConnection>
        
        init {
            masterPin = PinAffinityAndId.deserialize(byteIterator)
            val mutableSimpleConnections = mutableListOf<SimpleConnection>()
            
            while (byteIterator.hasNext()) {
                mutableSimpleConnections.add(SimpleConnection.deserialize(byteIterator))
            }
            
            connections = mutableSimpleConnections.toTypedArray()
        }
        
        override fun toString(): String {
            val str = masterPin.toString()
            
            return str + connections.joinToString { connection -> "${connection.toPin.toString()}(${connection.voltage})" }
        }
    }
    
    class OperationStatus() : MessageFromController {
        companion object {
            const val MESSAGE_OBTAINMENT_TIMEOUT_MS = 1000.toLong()
        }
        
        lateinit var response: ControllerResponse
            private set
        
        constructor(iterator: Iterator<Byte>) : this() {
            val byteValue = iterator.next()
            val enumConstant = ControllerResponse.values()
                .find { enumVal -> enumVal.byteValue == byteValue }
            
            if (enumConstant == null) {
                throw (IllegalArgumentException("Operation confirmation unsuccessful creation, byteValue: $byteValue"))
            }
            
            response = enumConstant
        }

        override fun toString(): String {
            return response.toString()
        }
    }
    
    class Boards(byteIterator: Iterator<Byte>) : MessageFromController {
        class InternalParameters(byteIterator: Iterator<Byte>) {
            companion object {
                const val RAW_VOLTAGE_TO_REAL_COEFF = 1.toFloat() / 1000.toFloat()
            }
            
            val outR1: CircuitParamT
            val inR1: CircuitParamT
            val outR2: CircuitParamT
            val inR2: CircuitParamT
            val shuntR: CircuitParamT
            val outVLow: CircuitParamT
            val outVHigh: CircuitParamT
            
            init {
                outR1 = (UShort(byteIterator).toFloat())
                inR1 = (UShort(byteIterator).toFloat())
                outR2 = (UShort(byteIterator).toFloat())
                inR2 = (UShort(byteIterator).toFloat())
                shuntR = (UShort(byteIterator).toFloat())
                outVLow = (UShort(byteIterator).toFloat() * RAW_VOLTAGE_TO_REAL_COEFF)
                outVHigh = (UShort(byteIterator).toFloat() * RAW_VOLTAGE_TO_REAL_COEFF)
            }
        }
        
        class Info(byteIterator: Iterator<Byte>) {
            val internals: InternalParameters
            val address: Byte
            val firmwareVersion: Byte
            val voltageLevel: IoBoardsManager.VoltageLevel
            val isHealthy: Boolean
            
            init {
                internals = InternalParameters(byteIterator)
                address = byteIterator.next()
                firmwareVersion = byteIterator.next()
                
                val voltageLevelByteVal = byteIterator.next()
                val vl = IoBoardsManager.VoltageLevel.values()
                    .find { e ->
                        e.byteValue == voltageLevelByteVal
                    }
                if (vl == null) {
                    throw IllegalArgumentException("Board with address $address has forbidden voltage level value: $voltageLevelByteVal")
                }
                voltageLevel = vl
                isHealthy = byteIterator.next() == 1.toByte()
            }
        }
        
        companion object {
            private const val MAX_ADDRESS = 127.toByte()
        }
        
        val boardsInfo: Array<Info>
        
        init {
            val mutableBoardsInfo = mutableListOf<Info>()
            while (byteIterator.hasNext()) {
                mutableBoardsInfo.add(Info(byteIterator))
            }
            
            boardsInfo = mutableBoardsInfo.toTypedArray()
        }
    }
    
    class Voltages(byteIterator: Iterator<Byte>) : MessageFromController {
        companion object {
            const val TIME_TO_WAIT_FOR_RESULT_MS = 2000.toLong()
        }
        
        data class PinVoltage(val iterator: Iterator<Byte>) {
            val pin: PinNumT
            val voltage: RawVoltageADCValue
            
            init {
                pin = iterator.next()
                    .toUByte()
                    .toInt()
                voltage = RawVoltageADCValue.deserialize(iterator)
            }
        }
        
        data class BoardVoltages(val iterator: Iterator<Byte>) {
            val boardId: BoardAddrT
            val pinsAndVoltages: Array<PinVoltage>
            
            init {
                boardId = iterator.next()
                    .toUByte()
                    .toInt()
                pinsAndVoltages = Array<PinVoltage>(IoBoard.PINS_COUNT_ON_SINGLE_BOARD) { PinVoltage(iterator) }
            }
        }
        
        val boardsAndVoltages: Array<BoardVoltages>
        
        init {
            val _boardsVoltages = mutableListOf<BoardVoltages>()
            
            while (byteIterator.hasNext()) {
                _boardsVoltages.add(BoardVoltages(byteIterator))
            }
            
            boardsAndVoltages = _boardsVoltages.toTypedArray()
        }
    }
    
    class Dummy : MessageFromController
    
    class KeepAlive : MessageFromController {
        companion object {
            const val KEEPALIVE_TIMEOUT_MS = 10_000.toLong()
        }
    }
}

fun Int.toByteArray(): ByteArray {
    val byteArray = ByteArray(Int.SIZE_BYTES)
    
    for (index in 0..(Int.SIZE_BYTES - 1)) {
        byteArray[index] = this.shr(8 * index)
            .toByte()
    }
    
    return byteArray
}

fun UShort(iterator: Iterator<Byte>): UShort {
    return iterator.next()
        .toUByte()
        .toUShort() or (iterator.next()
        .toUByte()
        .toInt() shl 8).toUShort()
}

fun Int(iterator: Iterator<Byte>): Int {
    return iterator.next()
        .toUByte()
        .toInt() or iterator.next()
        .toUByte()
        .toInt()
        .shl(8) or iterator.next()
        .toUByte()
        .toInt()
        .shl(8) or iterator.next()
        .toUByte()
        .toInt()
        .shl(8)
}