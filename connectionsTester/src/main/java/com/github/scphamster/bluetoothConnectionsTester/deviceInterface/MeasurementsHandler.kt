package com.github.scphamster.bluetoothConnectionsTester.deviceInterface

import java.lang.ref.WeakReference

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import com.github.scphamster.bluetoothConnectionsTester.*
import com.jaiselrahman.filepicker.model.MediaFile
import kotlinx.coroutines.*
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFRichTextString

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import com.github.scphamster.bluetoothConnectionsTester.deviceInterface.ControllerResponseInterpreter.Commands

typealias CommandArgsT = Int

class MeasurementsHandler(errorHandler: ErrorHandler,
                          bluetoothBridge: BluetoothBridge,
                          private val context: Context,
                          val coroutineScope: CoroutineScope) {

    class IOBoardState {
        var lastSelectedOutputPin: PinNumT = 0
            private set
        var boardsCount: PinNumT = 0
            private set
        val pinCount = boardsCount * numberOfPinsOnSingleBoard

        val _boardsCount = MutableLiveData<Int>()
        val boards = MutableLiveData<MutableList<MutableLiveData<IoBoard>>>()
        val pinsConnections = MutableLiveData<MutableList<Connections>>()
//            private set

        companion object {
            const val numberOfPinsOnSingleBoard = 32
        }
    }

    private data class Measurements(private var pinId: String = "", private var isConnectedTo: String = "") {}

    companion object {
        //        val Tag = this::class.simpleName.toString()
        val Tag = "CommandHandler"
        const val unused: Int = -1
    }

    var numberOfConnectedBoards = MutableLiveData<BoardCountT>()
        private set
        get
    val boardsManager by lazy { IoBoardsManager(errorHandler) }
    var outputFile: MediaFile? = null
    val responseInterpreter by lazy { ControllerResponseInterpreter() }
    private var connectionDescriptorMessageCounter = 0

    val commander = Commander(bluetoothBridge, context)

    init {
        responseInterpreter.onConnectionsDescriptionCallback = { new_connections ->
            boardsManager.updateConnectionsByControllerMsg(new_connections)
            connectionDescriptorMessageCounter++
        }
        responseInterpreter.onHardwareDescriptionCallback = { message ->
            coroutineScope.launch {
                boardsManager.updateIOBoards(message.boardsOnLine)
            }
        }

        commander.dataLink.onMessageReceivedCallback = { msg ->
            responseInterpreter.handleMessage(msg)
        }
    }

    suspend fun calibrate(completion_callback: ((String) -> Unit)) {
        val pin_count = boardsManager.getBoardsCount() * IoBoard.pinsCountOnSingleBoard
        if (pin_count == 0) completion_callback("Fail, no boards found yet")

        connectionDescriptorMessageCounter = 0
        commander.sendCommand(Commands.CheckConnectivity(Commands.CheckConnectivity.AnswerDomain.Resistance))
        val max_delay_for_result_arrival_ms = 1000


        withContext(Dispatchers.Default) {
            var pin_descriptor_messages_count_last_check = connectionDescriptorMessageCounter

            while (true) {
                delay(max_delay_for_result_arrival_ms.toLong())

                if (connectionDescriptorMessageCounter == pin_descriptor_messages_count_last_check) {
                    if (connectionDescriptorMessageCounter == pin_count) {
//                        boardsManager.calibrate()
                        completion_callback("Success, calibrated!")
                        return@withContext
                    }
                    else {
                        completion_callback("Fail! Only $pin_descriptor_messages_count_last_check descriptors arrived!")
                        return@withContext
                    }
                }
                else {
                    pin_descriptor_messages_count_last_check = connectionDescriptorMessageCounter
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast
            .makeText(context, msg, Toast.LENGTH_LONG)
            .show()
    }

    private fun boardsInitializer(boards_id: Array<IoBoardIndexT>) {
        val new_boards = mutableListOf<IoBoard>()
        var boards_counter = 0

        for (board in boards_id) {
            val new_board = IoBoard(board)
            val new_pin_group = PinGroup(boardsManager.nextUniqueBoardId)

            for (pin_num in 0..(IoBoard.pinsCountOnSingleBoard - 1)) {
                val descriptor = PinDescriptor(PinAffinityAndId(board, pin_num), group = new_pin_group)

                val new_pin = Pin(descriptor, belongsToBoard = WeakReference(new_board))
                new_board.pins.add(new_pin)
            }

            new_boards.add(new_board)
            boards_counter++
        }

        boardsManager.boards.value = new_boards
    }

    //todo: refactor
    suspend fun storeMeasurementsResultsToFile(maximumResistance: Float): Boolean {
        val pins_congregations = boardsManager.getPinsSortedByGroupOrAffinity()

        if (pins_congregations == null) {
            Log.e(Tag, "sorted pins array is null!")
            throw Error("Internal error")
        }

        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Measurements")
        val names_row = sheet.getRow(0) ?: sheet.createRow(0)

        var column_counter = 0
        for (congregation in pins_congregations) {
            var max_number_of_characters_in_this_column = 0

            val cell_with_name_of_congregation =
                names_row.getCell(column_counter) ?: names_row.createCell(column_counter)

            val name_of_congregation = if (congregation.isSortedByGroup) "Group: ${congregation.getCongregationName()}"
            else "Board Id: ${congregation.getCongregationName()}"

            Log.d(Tag, "Congregation: $name_of_congregation")

            val new_style = workbook.createCellStyle()
            new_style.setFillBackgroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.index)
            new_style.setFillPattern(FillPatternType.SQUARES)
            cell_with_name_of_congregation.setCellValue(name_of_congregation)
            cell_with_name_of_congregation.cellStyle = (new_style)

            val font_normal = workbook.createFont()
            val font_for_connection_with_changes = workbook.createFont()
            val font_for_unhealthy_pins = workbook
                .createFont()
            font_for_connection_with_changes.setColor(IndexedColors.ORANGE.index)
            font_for_unhealthy_pins.setColor(IndexedColors.RED.index)

            val results_start_row_number = 1
            for ((pin_counter, pin) in congregation.pins.withIndex()) {
                val row_num = pin_counter + results_start_row_number
                val row = sheet.getRow(row_num) ?: sheet.createRow(row_num)
                val cell_for_this_pin_connections =
                    row.getCell(column_counter) ?: row.createCell(column_counter)

                val string_builder = StringBuilder()
                string_builder.append("${pin.descriptor.getPrettyName()} -> ")
                val rich_text = XSSFRichTextString("${pin.descriptor.getPrettyName()} -> ")
                rich_text.applyFont(font_normal)

                if (pin.connections.size == 1 && pin.hasConnection(pin.descriptor.pinAffinityAndId) && pin.isHealthy) {
                    string_builder.append("NC")
                    rich_text.append("NC")
                }
                else if (!pin.isHealthy){
                    string_builder.append("UNHEALTHY!")
                    rich_text.append("UNHEALTHY!", font_for_unhealthy_pins)
                }
                else
                    for (connection in pin.connections) {
                        if (connection.toPin.pinAffinityAndId == pin.descriptor.affinityAndId) continue

                        //do not print if resistance is higher than max resistance (user defined)
                        val connection_as_string = if (connection.resistance != null) {
                            if (connection.resistance.value < maximumResistance) connection.toString() + ' '
                            else ""
                        }
                        else connection.toString()

                        if (connection.value_changed_from_previous_check) {
                            rich_text.append(connection_as_string, font_for_connection_with_changes)
                        }
                        else {
                            rich_text.append(connection_as_string, font_normal)
                        }

                        string_builder.append(connection_as_string)
                    }

                if (max_number_of_characters_in_this_column < string_builder.length) max_number_of_characters_in_this_column =
                    string_builder.length

                val style = workbook.createCellStyle()
                if (!pin.isHealthy) style.setFillBackgroundColor(IndexedColors.RED.index)
                else if (pin.connectionsListChangedFromPreviousCheck) style.setFillBackgroundColor(
                    IndexedColors.PINK.index)
                else style.setFillBackgroundColor((IndexedColors.WHITE.index))


                style.setFillPattern(FillPatternType.SQUARES)
                cell_for_this_pin_connections.cellStyle = style

                cell_for_this_pin_connections.setCellValue(rich_text)
            }

            //todo: add preference to make this action configurable
            val one_char_width = 260
            val max_column_width = 60 * one_char_width
            val column_width =
                if (one_char_width * max_number_of_characters_in_this_column > max_column_width) max_column_width
                else one_char_width * max_number_of_characters_in_this_column

            sheet.setColumnWidth(column_counter, column_width)
            column_counter++
        }

        Storage.storeToFile(workbook, context)

        return true
    }
}