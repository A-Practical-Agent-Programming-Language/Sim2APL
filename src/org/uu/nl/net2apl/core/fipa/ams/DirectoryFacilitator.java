package org.uu.nl.net2apl.core.fipa.ams;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import org.uu.nl.net2apl.core.agent.Agent;
import org.uu.nl.net2apl.core.agent.AgentArguments;
import org.uu.nl.net2apl.core.agent.AgentContextInterface;
import org.uu.nl.net2apl.core.agent.AgentCreationFailedException;
import org.uu.nl.net2apl.core.agent.AgentID;
import org.uu.nl.net2apl.core.agent.PlanToAgentInterface;
import org.uu.nl.net2apl.core.agent.Trigger;
import org.uu.nl.net2apl.core.fipa.acl.ACLMessage;
import org.uu.nl.net2apl.core.fipa.acl.Performative;
import org.uu.nl.net2apl.core.fipa.mts.Envelope;
import org.uu.nl.net2apl.core.logging.Loggable;
import org.uu.nl.net2apl.core.plan.Plan;
import org.uu.nl.net2apl.core.plan.PlanExecutionError;
import org.uu.nl.net2apl.core.plan.builtin.RunOncePlan;
import org.uu.nl.net2apl.core.plan.builtin.SubPlanInterface;
import org.uu.nl.net2apl.core.platform.Platform;

public class DirectoryFacilitator extends Agent {

	private static final Loggable logger = Platform.getLogger();
	
	static public enum RequestType {
		SUBSCRIBER_ADD,
		SUBSCRIBER_REMOVE,
		SERVICE_ADD,
		SERVICE_REMOVE,
		
		KILL_AGENT,
		
		UNKOWN
	}
	
	static private class MessageContent {
		public RequestType requestType = RequestType.UNKOWN;
		public AgentID agentId = null;
		public Set<String> serviceNames = new HashSet<>();
	}
	
	private static AgentArguments CreateAgentArguments(Set<AgentID> others) {
		final AgentArguments args = new AgentArguments();

		args.addContext(new DirectoryFacilitatorContext(others));
		args.addInitialPlan(getInitialPlan());
		args.addMessagePlanScheme(DirectoryFacilitator::handleMessageScheme);
		args.addExternalTriggerPlanScheme(DirectoryFacilitator::handleExternalScheme);

		return args;
	}

	private static Plan getInitialPlan() {
		return new RunOncePlan() {
			@Override
			public void executeOnce(PlanToAgentInterface planInterface) throws PlanExecutionError {
				try {
					List<Set<AgentID>> agentsLists = new ArrayList<Set<AgentID>>();
					agentsLists.add(planInterface.getAgent().getPlatform().getLocalAgentsSet()); // TODO: Add initial Agents to arguments (and eventually pass to Context?)
					agentsLists.add(planInterface.getContext(DirectoryFacilitatorContext.class).getOtherDFs());
					agentsLists.forEach((agents) ->
					agents.forEach((aid) -> {
						if (aid == planInterface.getAgentID()) {
							return;
						}

						Envelope envelope = new Envelope();
						envelope.setFrom(planInterface.getAgentID());
						envelope.addTo(aid);
						envelope.addIntendedReceiver(aid);

						ACLMessage message = new ACLMessage(Performative.PROPOSE);
						message.addReceiver(aid);
						message.addReplyTo(planInterface.getAgentID());
						message.setSender(planInterface.getAgentID());
						message.setContent("propose-registration"); // NOTE: Not used! The 'PROPOSE' performative is enough for now.
						message.setEnvelope(envelope);

						try {
							planInterface.getAgent().sendMessage(message);
						} catch (Exception ex) {
							logger.log(DirectoryFacilitator.class, ex);
						}
					}));
				} catch (Exception ex) {
					logger.log(DirectoryFacilitator.class, Level.WARNING, ex);
				}
			}
		};
	}

	private static void replySubscriber(
		PlanToAgentInterface planInterface,
		AgentID aid,
		String conversationId,
		Performative replyPerformative,
		String serviceType,
		Collection<AgentID> services) {
		
		StringBuilder sb = new StringBuilder();
		services.forEach( (serviceId) -> sb.append( serviceId.toString() + " " ) );
		
		final Envelope envelope = new Envelope();
		envelope.setFrom(planInterface.getAgentID());
		envelope.addTo(aid);
		envelope.addIntendedReceiver(aid);

		final ACLMessage response = new ACLMessage(replyPerformative);
		response.setSender(planInterface.getAgentID());
		response.addReceiver(aid);
		response.setConversationId(conversationId);
		response.setContent(serviceType + " " + sb.toString());
		response.setEnvelope(envelope);

		logger.log(DirectoryFacilitator.class, Level.FINER, aid.toString() + " <<< " + replyPerformative.toString() + " |" + serviceType + " " + sb.toString());
		
		try {
			planInterface.getAgent().sendMessage(response);
		} catch (Exception ex) {
			logger.log(DirectoryFacilitator.class, Level.WARNING, ex);
		}
	}

	private static void forwardToOtherDf(
		PlanToAgentInterface planInterface,
		Collection<AgentID> otherDfs,
		String conversationId,
		String forwardedContent) {
		
		otherDfs.forEach((AgentID recipientAid) -> {
			final Envelope envelope = new Envelope();
			envelope.setFrom(planInterface.getAgentID());
		    envelope.addTo(recipientAid);
			envelope.addIntendedReceiver(recipientAid);
			
			final ACLMessage response = new ACLMessage(Performative.PROXY);
			response.setSender(planInterface.getAgentID());
		    response.addReceiver(recipientAid);
		    response.setConversationId(conversationId);
			response.setContent(forwardedContent);
			response.setEnvelope(envelope);
			
			try {
				planInterface.getAgent().sendMessage(response);
			} catch (Exception ex) {
				logger.log(DirectoryFacilitator.class, Level.WARNING, ex);
			}
		});
	}

	private static DirectoryFacilitatorContext getDFContext(PlanToAgentInterface planInterface) {
		return planInterface.getContext(DirectoryFacilitatorContext.class);
	}

	/** Messages to the DF mostly follow the same structure. */
	private static MessageContent parseMessageContent(DirectoryFacilitatorContext dfContext , String content) {
		MessageContent result = new MessageContent();
		
		try {
		
			String[] words = content.split(" ");
			int numWords = words.length;
			
			result.requestType = RequestType.valueOf(words[0].toUpperCase());
			result.agentId = new AgentID(new URI(words[1]));
			for (int i = 2; i < numWords; ++i) {
				result.serviceNames.add(words[i]);
			}
			
			//dfContext.resetNickName( result.agentId );
			
		} catch (URISyntaxException ex) {
			result.requestType = RequestType.UNKOWN;
			logger.log(DirectoryFacilitator.class, Level.WARNING, "Couldn't parse message-content: " + content);
			logger.log(DirectoryFacilitator.class, Level.WARNING, "  \\-> reason: " + ex.getReason());
		}
		
		return result;
	}
	
	private static SubPlanInterface handleMessageScheme(final Trigger trigger,
			final AgentContextInterface contextInterface) {
		if (trigger instanceof ACLMessage) {
			final ACLMessage received = (ACLMessage) trigger;
			final Performative performative = received.getPerformative();
			
			switch (performative) {

			case PROXY:
				// NOTE: Intentional fallthrough.

			case CANCEL:
				// NOTE: Intentional fallthrough.
				
			case SUBSCRIBE:
				return (planInterface) -> {
					
					logger.log(DirectoryFacilitator.class, Level.FINER, received.getEnvelope().getFrom().toString() + " ??? " + performative.toString() + " |" + received.getContent());
					
					final DirectoryFacilitatorContext dfContext = getDFContext(planInterface);
					//dfContext.saveNickname(received.getEnvelope().getFrom());
					//received.getEnvelope().getAllTo().forEachRemaining( (aid) -> dfContext.saveNickname(aid) );
					
					final MessageContent content = parseMessageContent(dfContext, received.getContent());
					
					boolean asProxy = (performative == Performative.PROXY);
					Performative replyPerformative = Performative.FAILURE;
					
					for (String service : content.serviceNames) {
						
						Set<AgentID> subsAgents = new HashSet<>();
						Set<AgentID> serviceAgents = new HashSet<>();
						
						switch(content.requestType) {
						
						case SUBSCRIBER_ADD:
							subsAgents.add(content.agentId);
							serviceAgents.addAll( dfContext.subscribe(service, content.agentId) );
							replyPerformative = Performative.INFORM;
							break;
							
						case SUBSCRIBER_REMOVE:
                            dfContext.unsubscribe(service, content.agentId);
                            replyPerformative = Performative.AGREE; // Not actually sent presently.
							break;
							
						case SERVICE_ADD:
							subsAgents.addAll( dfContext.register(service, content.agentId) );
							serviceAgents.add(content.agentId);
							replyPerformative = Performative.INFORM;
							break;
						
						case KILL_AGENT:
							dfContext.unsubscribe(content.agentId);
							// NOTE: Intentional fallthrough!
							
						case SERVICE_REMOVE:
							subsAgents.addAll( dfContext.deregister(content.agentId) );
							serviceAgents.add(content.agentId);
							replyPerformative = Performative.CANCEL;
							break;
							
						case UNKOWN:
							logger.log(DirectoryFacilitator.class, Level.WARNING, "Unknown SUBSCRIBE request-type.");
							break;
							
						}
						final Performative replyPerformativeFinal = replyPerformative;
						
						if (serviceAgents.size() > 0) { // NOTE: Should we sent something if 0 (with a different performative maybe)? Or just remove this 'if' and let the list be empty?
							subsAgents.forEach( (subsAgent) ->
							    replySubscriber( planInterface, subsAgent, received.getConversationId(), replyPerformativeFinal, service, serviceAgents )
							);
						}
					}
					
					if (! asProxy) { // If we're not a proxy ourselves this turn, notify other known DFs of the request.
						forwardToOtherDf( planInterface, dfContext.getOtherDFs(), received.getConversationId(), received.getContent() );
					}
					
				};
			
			case PROPOSE: // Some other DF just proposed that we should register.
				          // If it's on another system, this can only be because we're already known to it, so just add the sender to 'otherDF's.
				          // (NOTE: Would this behaviour ever change? -> .. but we're probably going to redo this anyway once we start to build the federated network.) 
				return (planInterface) -> {
					planInterface.getContext(DirectoryFacilitatorContext.class).addOtherDF(received.getSender());
					logger.log(DirectoryFacilitator.class, Level.FINER, "DF: I now know another DF (via remote Message): " + received.getSender());
				};
			
//			case QUERY_REF: // Answer 'search' query for a service.
//				return (planInterface) -> {
//              // .... TODO?
//				};
	
			case NOT_UNDERSTOOD:
				return (planInterface) -> {
					logger.log(DirectoryFacilitator.class, Level.WARNING, "Someone didn't understand a message I sent!");
					// TODO: Send message back.
				};
				
			default: // No other performatives are understood.
				return (planInterface) -> {
					logger.log(DirectoryFacilitator.class, Level.WARNING, "DF: Performative " + performative + " not understood!");
//					replyMessage(planInterface, received, Performative.NOT_UNDERSTOOD,
//							"Performative not supported by DirectoryFacilitator."); // TODO what is our message-content
//																					// onthology?
				};
			}
		} else {
			return SubPlanInterface.UNINSTANTIATED;
		}
	}
	
	private static SubPlanInterface handleExternalScheme(final Trigger trigger, final AgentContextInterface contextInterface) {
		if (trigger instanceof ReceiveRemoteAddress) {
			final AgentID remoteID = ((ReceiveRemoteAddress) trigger).getAgentID();
			return (planInterface) -> {
				planInterface.getContext(DirectoryFacilitatorContext.class).addOtherDF(remoteID);
				logger.log(DirectoryFacilitator.class, Level.FINER, "DF: I now know another DF (directly by NetNode): " + remoteID);
			};
		} else {
			return SubPlanInterface.UNINSTANTIATED;
		}
	}
	
	public DirectoryFacilitator(Platform p, Set<AgentID> others, AgentID agentID) throws AgentCreationFailedException {
		super(p, CreateAgentArguments(others), agentID);
		p.registerDirectoryFacilitator(this);
	}

	public DirectoryFacilitator(Platform p, Set<AgentID> others) throws AgentCreationFailedException, URISyntaxException {
		super(p, CreateAgentArguments(others));
		p.registerDirectoryFacilitator(this);
	}
	
	public DirectoryFacilitator(Platform p) throws AgentCreationFailedException, URISyntaxException {
		this(p, null);
	}
}
