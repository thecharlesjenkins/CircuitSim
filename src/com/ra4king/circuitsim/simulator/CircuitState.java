package com.ra4king.circuitsim.simulator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.ra4king.circuitsim.simulator.Port.Link;
import com.ra4king.circuitsim.simulator.WireValue.State;

public class CircuitState {
	private Circuit circuit;
	private Map<Component, Object> componentProperties;
	private Map<Link, LinkState> linkStates;
	
	private final boolean readOnly;
	
	/**
	 * Create a new CircuitState based on the given Circuit. It is added to the Circuit's list of states.
	 *
	 * @param circuit The Circuit which this CircuitState represents.
	 */
	public CircuitState(Circuit circuit) {
		if(circuit == null) {
			throw new NullPointerException("Circuit cannot be null.");
		}
		
		this.readOnly = false;
		
		circuit.getSimulator().runSync(() -> {
			this.circuit = circuit;
			this.componentProperties = new HashMap<>();
			this.linkStates = new HashMap<>();
			circuit.getCircuitStates().add(this);
		});
	}
	
	/**
	 * Clones the CircuitState for read-only usage. It is NOT added to the Circuit's list of states.
	 *
	 * @param state The CircuitState to clone.
	 */
	public CircuitState(CircuitState state) {
		this.readOnly = true;
		
		state.circuit.getSimulator().runSync(() -> {
			this.circuit = state.circuit;
			this.componentProperties = new HashMap<>(state.componentProperties);
			this.linkStates = new HashMap<>();
			state.linkStates.forEach((link, linkState) -> this.linkStates.put(link, new LinkState(linkState)));
		});
	}
	
	public Circuit getCircuit() {
		return circuit;
	}
	
	public Map<Component, Object> getComponentProperties() {
		return componentProperties;
	}
	
	public Object getComponentProperty(Component component) {
		return componentProperties.get(component);
	}
	
	public void putComponentProperty(Component component, Object property) {
		componentProperties.put(component, property);
	}
	
	public Object removeComponentProperty(Component component) {
		return componentProperties.remove(component);
	}
	
	/**
	 * Get the current true value on the Link, which is the merging of all pushed values.
	 *
	 * @param link The Link for which the value is returned.
	 * @return The value of the Link.
	 */
	public WireValue getMergedValue(Link link) {
		return get(link).getMergedValue();
	}
	
	/**
	 * Get the last value received by this Port.
	 *
	 * @param port The Port for which the last received value is returned.
	 * @return The last received value of the Port.
	 */
	public WireValue getLastReceived(Port port) {
		return new WireValue(get(port.getLink()).getLastReceived(port));
	}
	
	/**
	 * Get the last value pushed by this Port.
	 *
	 * @param port The Port for which the last pushed value is returned.
	 * @return The last pushed value of the Port.
	 */
	public WireValue getLastPushed(Port port) {
		return new WireValue(get(port.getLink()).getLastPushed(port));
	}
	
	public boolean isShortCircuited(Link link) {
		return get(link).isShortCircuit();
	}
	
	/**
	 * Resets this CircuitState, clearing all pushed and received values.
	 * Each Component's {@code uninit(this)} then {@code init(this, null)} methods are called.
	 */
	public void reset() {
		linkStates.putAll(linkStates.keySet().stream().collect(Collectors.toMap(link -> link, LinkState::new)));
		
		circuit.getComponents().forEach(c -> {
			c.uninit(this);
			c.init(this, null);
		});
	}
	
	private LinkState get(Link link) {
		if(!linkStates.containsKey(link)) {
			if(link.getCircuit() == null) {
				throw new IllegalArgumentException("Link has no circuit!");
			}
			
			if(link.getCircuit() != circuit) {
				throw new IllegalArgumentException("Link not from this circuit.");
			}
			
			LinkState linkState = new LinkState(link);
			linkStates.put(link, linkState);
			return linkState;
		}
		
		return linkStates.get(link);
	}
	
	void link(Link link1, Link link2) {
		circuit.getSimulator().runSync(() -> get(link1).link(get(link2)));
	}
	
	void unlink(Link link, Port port) {
		circuit.getSimulator().runSync(() -> get(link).unlink(port));
	}
	
	void propagateSignal(Link link) {
		LinkState linkState = get(link);
		
		linkState.participants.forEach((port, info) -> {
			WireValue lastMerged = info.lastMerged;
			WireValue lastPushed = info.lastPushed;
			
			if(!lastMerged.equals(lastPushed)) {
				linkState.cachedMergedValue = null;
				linkState.isShortCircuited = null;
				lastMerged.set(lastPushed);
			}
		});
		
		linkState.propagate();
	}
	
	/**
	 * Push a new value from the specified Port. The Simulator instance attached to the Circuit is notified.
	 * An IllegalStateException is thrown if this CircuitState is read-only.
	 *
	 * @param port  The Port pushing the value.
	 * @param value The value being pushed.
	 */
	public void pushValue(Port port, WireValue value) {
		if(readOnly) {
			throw new IllegalStateException("This CircuitState is read-only");
		}
		
		circuit.getSimulator().runSync(() -> {
			LinkState linkState = get(port.getLink());
			
			WireValue lastPushed = linkState.getLastPushed(port);
			if(!value.equals(lastPushed)) {
				lastPushed.set(value);
				circuit.getSimulator().valueChanged(this, port);
			}
		});
	}
	
	void ensureUnlinked(Component component) {
		for(int i = 0; i < component.getNumPorts(); i++) {
			Port port = component.getPort(i);
			Link link = port.getLink();
			if(link != null && linkStates.containsKey(link) && linkStates.get(link).participants.size() > 1) {
				throw new RuntimeException("Must unlink port before removing it.");
			}
		}
	}
	
	class LinkState {
		final Link link;
		final HashMap<Port, PortStateInfo> participants;
		WireValue cachedMergedValue;
		Boolean isShortCircuited;
		
		class PortStateInfo {
			WireValue lastPushed;
			WireValue lastMerged;
			WireValue lastReceived;
			
			PortStateInfo() {
				this(new WireValue(link.getBitSize()),
				     new WireValue(link.getBitSize()),
				     new WireValue(link.getBitSize()));
			}
			
			PortStateInfo(PortStateInfo info) {
				this(new WireValue(info.lastPushed),
				     new WireValue(info.lastMerged),
				     new WireValue(info.lastReceived));
			}
			
			PortStateInfo(WireValue lastPushed, WireValue lastMerged, WireValue lastReceived) {
				this.lastPushed = lastPushed;
				this.lastMerged = lastMerged;
				this.lastReceived = lastReceived;
			}
		}
		
		LinkState(Link link) {
			this.link = link;
			participants = new HashMap<>();
			link.getParticipants().forEach(port -> participants.put(port, new PortStateInfo()));
		}
		
		LinkState(LinkState linkState) {
			link = linkState.link;
			participants = new HashMap<>();
			linkState.participants.forEach((port, info) -> participants.put(port, new PortStateInfo(info)));
		}
		
		WireValue getLastPushed(Port port) {
			return participants.get(port).lastPushed;
		}
		
		WireValue getLastMerged(Port port) {
			return participants.get(port).lastMerged;
		}
		
		WireValue getLastReceived(Port port) {
			return participants.get(port).lastReceived;
		}
		
		WireValue getIncomingValue(Port port) {
			WireValue newValue = new WireValue(link.getBitSize());
			participants.forEach((p, info) -> {
				if(p != port) {
					newValue.merge(info.lastMerged);
				}
			});
			return newValue;
		}
		
		WireValue getMergedValue() {
			if(cachedMergedValue != null) return cachedMergedValue;
			
			WireValue newValue = new WireValue(link.getBitSize());
			participants.values().forEach(info -> newValue.merge(info.lastMerged));
			
			cachedMergedValue = new WireValue(newValue);
			isShortCircuited = null;
			
			return newValue;
		}
		
		boolean isShortCircuit() {
			try {
				if(isShortCircuited != null) return isShortCircuited;
				
				getMergedValue();
				return isShortCircuited = false;
			} catch(ShortCircuitException exc) {
				return isShortCircuited = true;
			} catch(Throwable t) {
				return isShortCircuited = false;
			}
		}
		
		void propagate() {
			Set<Port> toNotify = new HashSet<>();
			
			for(Port participantPort : participants.keySet()) {
				WireValue incomingValue = getIncomingValue(participantPort);
				WireValue lastReceived = getLastReceived(participantPort);
				if(!lastReceived.equals(incomingValue)) {
					lastReceived.set(incomingValue);
					toNotify.add(participantPort);
				}
			}
			
			RuntimeException exception = null;
			
			for(Port participantPort : toNotify) {
				try {
					participantPort.getComponent().valueChanged(CircuitState.this,
					                                            getLastReceived(participantPort),
					                                            participantPort.getPortIndex());
				} catch(RuntimeException exc) {
					if(exception == null) { // grab the first one
						exception = exc;
					}
				}
			}
			
			if(exception != null) {
				throw exception;
			}
		}
		
		void link(LinkState other) {
			if(this == other) return;
			
			participants.putAll(other.participants);
			
			cachedMergedValue = null;
			isShortCircuited = null;
			participants.forEach((port, info) -> info.lastMerged.setAllBits(State.X));
			
			linkStates.remove(other.link);
			
			getCircuit().getSimulator().valueChanged(CircuitState.this, link);
		}
		
		void unlink(Port port) {
			if(!participants.containsKey(port)) return;
			
			cachedMergedValue = null;
			isShortCircuited = null;
			
			PortStateInfo info = participants.remove(port);
			get(port.getLink()).participants.put(port, new PortStateInfo(info.lastPushed,
			                                                             new WireValue(info.lastPushed),
			                                                             new WireValue(link.getBitSize())));
			
			RuntimeException exception = null;
			
			WireValue newValue = new WireValue(link.getBitSize());
			if(!info.lastReceived.equals(newValue)) {
				info.lastReceived.set(newValue);
				try {
					port.getComponent().valueChanged(CircuitState.this, newValue, port.getPortIndex());
				} catch(RuntimeException exc) {
					exception = exc;
				}
			}
			
			if(participants.isEmpty()) {
				linkStates.remove(link);
			} else {
				getCircuit().getSimulator().valueChanged(CircuitState.this, link);
			}
			
			if(exception != null) {
				throw exception;
			}
		}
	}
}
