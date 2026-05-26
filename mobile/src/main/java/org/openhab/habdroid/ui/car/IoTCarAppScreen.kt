/*
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.ui.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openhab.habdroid.R
import org.openhab.habdroid.model.Item
import org.openhab.habdroid.model.toParsedState
import org.openhab.habdroid.util.HttpClient
import org.openhab.habdroid.util.ItemClient
import org.openhab.habdroid.util.getConnectionFactory

class IoTCarAppScreen(carContext: CarContext) : Screen(carContext) {
    private var isLoading = true
    private var errorMessage: String? = null
    private var items: List<Item> = emptyList()
    private var itemMap: MutableMap<String, Item> = mutableMapOf()
    private var sseJob: Job? = null

    init {
        loadData()
    }

    private fun loadData() {
        isLoading = true
        errorMessage = null
        invalidate()

        lifecycleScope.launch {
            try {
                val connectionFactory = carContext.getConnectionFactory()
                val connection = connectionFactory.currentActive?.conn?.connection
                    ?: connectionFactory.currentPrimary?.conn?.connection

                if (connection == null) {
                    isLoading = false
                    errorMessage = "No active connection to openHAB server found. Please set up the server connection in the phone app."
                    invalidate()
                    return@launch
                }

                val loaded = withContext(Dispatchers.IO) {
                    try {
                        ItemClient.loadItems(connection)
                    } catch (e: HttpClient.HttpException) {
                        null
                    }
                }

                if (loaded == null) {
                    isLoading = false
                    errorMessage = "Failed to load items. Check your server connection."
                    invalidate()
                    return@launch
                }

                val actionableTypes = listOf(
                    Item.Type.Switch,
                    Item.Type.Dimmer,
                    Item.Type.Rollershutter,
                    Item.Type.Color,
                    Item.Type.Player
                )
                items = loaded.filter { item ->
                    !item.readOnly && !item.label.isNullOrEmpty() && (
                        item.type in actionableTypes || item.groupType in actionableTypes
                    )
                }
                itemMap = items.associateBy { it.name }.toMutableMap()
                isLoading = false
                invalidate()

                // Start SSE listener for real-time updates
                sseJob?.cancel()
                sseJob = launch {
                    try {
                        ItemClient.listenForItemChange(this, connection, null)
                            .consumeEach { (itemName, stateValue) ->
                                val currentItem = itemMap[itemName]
                                if (currentItem != null) {
                                    val updatedItem = currentItem.copy(state = stateValue.toParsedState())
                                    itemMap[itemName] = updatedItem
                                    items = items.map { if (it.name == itemName) updatedItem else it }
                                    invalidate()
                                }
                            }
                    } catch (e: Exception) {
                        Log.e("IoTCarAppScreen", "SSE listener error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("IoTCarAppScreen", "Error loading data", e)
                isLoading = false
                errorMessage = e.message ?: "An error occurred."
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (isLoading) {
            val header = Header.Builder()
                .setTitle(carContext.getString(R.string.app_name))
                .build()
            return ListTemplate.Builder()
                .setHeader(header)
                .setLoading(true)
                .build()
        }

        val err = errorMessage
        if (err != null) {
            val header = Header.Builder()
                .setTitle(carContext.getString(R.string.app_name))
                .setStartHeaderAction(Action.APP_ICON)
                .build()
            return MessageTemplate.Builder(err)
                .setHeader(header)
                .addAction(
                    Action.Builder()
                        .setTitle("Retry")
                        .setOnClickListener { loadData() }
                        .build()
                )
                .build()
        }

        if (items.isEmpty()) {
            val header = Header.Builder()
                .setTitle(carContext.getString(R.string.app_name))
                .setStartHeaderAction(Action.APP_ICON)
                .build()
            return MessageTemplate.Builder("No controllable IoT devices found.")
                .setHeader(header)
                .addAction(
                    Action.Builder()
                        .setTitle("Refresh")
                        .setOnClickListener { loadData() }
                        .build()
                )
                .build()
        }

        val itemListBuilder = ItemList.Builder()
        items.forEach { item ->
            val isChecked = isItemChecked(item)
            val toggle = Toggle.Builder { checked ->
                sendItemCommand(item, checked)
            }
            .setChecked(isChecked)
            .build()

            val subtitleText = item.state?.asString ?: ""

            val row = Row.Builder()
                .setTitle(item.label ?: item.name)
                .addText(subtitleText)
                .setToggle(toggle)
                .build()

            itemListBuilder.addItem(row)
        }

        val header = Header.Builder()
            .setTitle("IoT Controls")
            .setStartHeaderAction(Action.APP_ICON)
            .addEndHeaderAction(
                Action.Builder()
                    .setTitle("Refresh")
                    .setOnClickListener { loadData() }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setSingleList(itemListBuilder.build())
            .setHeader(header)
            .build()
    }

    private fun isItemChecked(item: Item): Boolean {
        return when {
            item.isOfTypeOrGroupType(Item.Type.Player) -> item.state?.asString == "PLAY"
            item.isOfTypeOrGroupType(Item.Type.Rollershutter) -> {
                val num = item.state?.asString?.toFloatOrNull() ?: 0f
                num > 0f
            }
            else -> item.state?.asBoolean ?: false
        }
    }

    private fun sendItemCommand(item: Item, checked: Boolean) {
        val command = when {
            item.isOfTypeOrGroupType(Item.Type.Player) -> if (checked) "PLAY" else "PAUSE"
            item.isOfTypeOrGroupType(Item.Type.Rollershutter) -> if (checked) "DOWN" else "UP"
            else -> if (checked) "ON" else "OFF"
        }

        lifecycleScope.launch {
            try {
                val connectionFactory = carContext.getConnectionFactory()
                val connection = connectionFactory.currentActive?.conn?.connection
                    ?: connectionFactory.currentPrimary?.conn?.connection
                if (connection != null) {
                    withContext(Dispatchers.IO) {
                        connection.httpClient.post("rest/items/${item.name}", command).asStatus()
                    }
                    // Optimistic UI update
                    val updatedItem = item.copy(state = command.toParsedState())
                    itemMap[item.name] = updatedItem
                    items = items.map { if (it.name == item.name) updatedItem else it }
                    invalidate()
                }
            } catch (e: Exception) {
                Log.e("IoTCarAppScreen", "Error sending command", e)
            }
        }
    }
}
