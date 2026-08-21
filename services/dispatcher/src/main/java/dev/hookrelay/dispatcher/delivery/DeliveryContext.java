package dev.hookrelay.dispatcher.delivery;

import dev.hookrelay.contracts.DeliveryTask;
import dev.hookrelay.contracts.EndpointConfig;

/** Bir teslimat denemesi boyunca tasinan veri. */
public record DeliveryContext(DeliveryTask task, EndpointConfig endpoint) {

    public int attempt() { return task.attempt(); }
}
