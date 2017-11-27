package org.eclipse.lyo.store.update.handlers;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.List;
import java.util.TimeZone;

import javax.xml.datatype.DatatypeConfigurationException;

import org.eclipse.lyo.core.trs.ChangeEvent;
import org.eclipse.lyo.core.trs.Creation;
import org.eclipse.lyo.core.trs.Deletion;
import org.eclipse.lyo.core.trs.Modification;
import org.eclipse.lyo.oslc4j.core.exception.OslcCoreApplicationException;
import org.eclipse.lyo.oslc4j.core.model.AbstractResource;
import org.eclipse.lyo.oslc4j.provider.jena.JenaModelHelper;
import org.eclipse.lyo.oslc4j.trs.provider.ChangeHistories;
import org.eclipse.lyo.oslc4j.trs.provider.HistoryData;
import org.eclipse.lyo.store.update.change.Change;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.jena.rdf.model.Model;

public class TrsMqttChangeLogHandler<T> extends TrsChangelogHandler<T> {
    private final Logger log = LoggerFactory.getLogger(TrsMqttChangeLogHandler.class);

    private final MqttClient mqttClient;
    private final String topic;
    private int order;

    public TrsMqttChangeLogHandler(final ChangeHistories changeLog, final MqttClient mqttClient, final String topic) {
        super(changeLog);
        this.mqttClient = mqttClient;
        this.topic = topic;
        this.order = changeLog.getHistorySize();
    }

    @Override
    protected List<HistoryData> updateChangelogHistory(Collection<Change<T>> changes) {
        List<HistoryData> updateChangelogHistory = super.updateChangelogHistory(changes);
        for (HistoryData historyData : updateChangelogHistory) {
            AbstractResource res = trsChangeResourceFrom(historyData);
            MqttMessage message = buildMqttMessage(res);
            try {
                mqttClient.publish(topic, message);
            } catch (MqttException e) {
                log.error("Can't publish the message to the MQTT channel", e);
            }
        }
        return updateChangelogHistory;
    }

    private MqttMessage buildMqttMessage(AbstractResource res) {
        try {
            Model changeEventJenaModel = JenaModelHelper.createJenaModel(new Object[] { res });
            MqttMessage message = new MqttMessage();
            message.setPayload(changeEventJenaModel.toString().getBytes());
            return message;
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException
                | DatatypeConfigurationException | OslcCoreApplicationException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private AbstractResource trsChangeResourceFrom(HistoryData historyData) {
        this.order += 1;
        String histDataType = historyData.getType();
        URI uri = historyData.getUri();
        URI changedUri;
        try {
            TimeZone tz = TimeZone.getTimeZone("UTC");
            DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'"); // Quoted "Z" to indicate UTC, no timezone offset
            df.setTimeZone(tz);
            String nowAsISO = df.format(historyData.getTimestamp());
            changedUri = new URI("urn:x-trs:" + nowAsISO + ":" + this.order);
            ChangeEvent ce;
            if (histDataType == HistoryData.CREATED) {
                ce = new Creation(changedUri, uri, this.order);
            } else if (histDataType == HistoryData.MODIFIED) {
                ce = new Modification(changedUri, uri, this.order);
            } else {
                ce = new Deletion(changedUri, uri, this.order);
            }
            return ce;
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

}
