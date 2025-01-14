package io.tapdata.flow.engine.V2.task.impl;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.config.ProcessingGuarantee;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Vertex;
import com.tapdata.cache.ICacheService;
import com.tapdata.cache.hazelcast.HazelcastCacheService;
import com.tapdata.constant.*;
import com.tapdata.entity.Connections;
import com.tapdata.entity.DatabaseTypeEnum;
import com.tapdata.entity.Milestone;
import com.tapdata.entity.RelateDataBaseTable;
import com.tapdata.entity.task.context.DataProcessorContext;
import com.tapdata.entity.task.context.ProcessorBaseContext;
import com.tapdata.mongo.ClientMongoOperator;
import com.tapdata.mongo.HttpClientMongoOperator;
import com.tapdata.tm.commons.dag.Edge;
import com.tapdata.tm.commons.dag.Element;
import com.tapdata.tm.commons.dag.Node;
import com.tapdata.tm.commons.dag.nodes.CacheNode;
import com.tapdata.tm.commons.dag.nodes.DataNode;
import com.tapdata.tm.commons.dag.nodes.DatabaseNode;
import com.tapdata.tm.commons.dag.nodes.TableNode;
import com.tapdata.tm.commons.dag.process.MergeTableNode;
import com.tapdata.tm.commons.task.dto.SubTaskDto;
import io.tapdata.common.SettingService;
import io.tapdata.common.sharecdc.ShareCdcUtil;
import io.tapdata.dao.MessageDao;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.flow.engine.V2.common.node.NodeTypeEnum;
import io.tapdata.flow.engine.V2.entity.JetDag;
import io.tapdata.flow.engine.V2.exception.node.NodeException;
import io.tapdata.flow.engine.V2.node.hazelcast.HazelcastBaseNode;
import io.tapdata.flow.engine.V2.node.hazelcast.data.*;
import io.tapdata.flow.engine.V2.node.hazelcast.data.pdk.HazelcastPdkSourceAndTargetTableNode;
import io.tapdata.flow.engine.V2.node.hazelcast.data.pdk.HazelcastSourcePdkDataNode;
import io.tapdata.flow.engine.V2.node.hazelcast.data.pdk.HazelcastTargetPdkDataNode;
import io.tapdata.flow.engine.V2.node.hazelcast.processor.HazelcastCustomProcessor;
import io.tapdata.flow.engine.V2.node.hazelcast.processor.HazelcastMergeNode;
import io.tapdata.flow.engine.V2.node.hazelcast.processor.HazelcastProcessorNode;
import io.tapdata.flow.engine.V2.node.hazelcast.processor.join.HazelcastJoinProcessor;
import io.tapdata.flow.engine.V2.task.TaskClient;
import io.tapdata.flow.engine.V2.task.TaskService;
import io.tapdata.flow.engine.V2.util.GraphUtil;
import io.tapdata.flow.engine.V2.util.MergeTableUtil;
import io.tapdata.flow.engine.V2.util.NodeUtil;
import io.tapdata.flow.engine.V2.util.PdkUtil;
import io.tapdata.milestone.MilestoneContext;
import io.tapdata.milestone.MilestoneFactory;
import io.tapdata.milestone.MilestoneFlowServiceJetV2;
import io.tapdata.milestone.MilestoneJetEdgeService;
import io.tapdata.schema.TapTableMap;
import io.tapdata.schema.TapTableUtil;
import lombok.SneakyThrows;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * @author jackin
 * @date 2021/12/1 8:56 PM
 **/
@Service
@DependsOn("tapdataTaskScheduler")
public class HazelcastTaskService implements TaskService<SubTaskDto> {

	private static final Logger logger = LogManager.getLogger(HazelcastTaskService.class);

	private static HazelcastInstance hazelcastInstance;

	@Autowired
	private ConfigurationCenter configurationCenter;

	private static ClientMongoOperator clientMongoOperator;

	@Autowired
	private SettingService settingService;

	@Autowired
	private MessageDao messageDao;

	private static ICacheService cacheService;

	public HazelcastTaskService(ClientMongoOperator clientMongoOperator) {
		if (HazelcastTaskService.clientMongoOperator == null) {
			HazelcastTaskService.clientMongoOperator = clientMongoOperator;
		}
	}

	@PostConstruct
	public void init() {
		String agentId = (String) configurationCenter.getConfig(ConfigurationCenter.AGENT_ID);
		Config config = HazelcastUtil.getConfig(agentId);
		ShareCdcUtil.initHazelcastPersistenceStorage(config, settingService, clientMongoOperator);
		hazelcastInstance = Hazelcast.newHazelcastInstance(config);
		cacheService = new HazelcastCacheService(hazelcastInstance, clientMongoOperator);
		messageDao.setCacheService(cacheService);
	}

	public static HazelcastInstance getHazelcastInstance() {
		return hazelcastInstance;
	}

	@Override
	public TaskClient<SubTaskDto> startTask(SubTaskDto subTaskDto) {

		final JetDag jetDag = task2HazelcastDAG(subTaskDto);
		MilestoneFlowServiceJetV2 milestoneFlowServiceJetV2 = initMilestone(subTaskDto);

		JobConfig jobConfig = new JobConfig();
		jobConfig.setName(subTaskDto.getName() + "-" + subTaskDto.getId().toHexString());
		jobConfig.setProcessingGuarantee(ProcessingGuarantee.AT_LEAST_ONCE);
		final Job job = hazelcastInstance.getJet().newJob(jetDag.getDag(), jobConfig);
		return new HazelcastTaskClient(job, subTaskDto, clientMongoOperator, configurationCenter, hazelcastInstance, milestoneFlowServiceJetV2);
	}

	@SneakyThrows
	private JetDag task2HazelcastDAG(SubTaskDto subTaskDto) {

		DAG dag = new DAG();

		final List<Node> nodes = subTaskDto.getDag().getNodes();
		final List<Edge> edges = subTaskDto.getDag().getEdges();
		Map<String, Vertex> vertexMap = new HashMap<>();
		Map<String, AbstractProcessor> hazelcastBaseNodeMap = new HashMap<>();
		Map<String, AbstractProcessor> typeConvertMap = new HashMap<>();
		Map<String, Node<?>> nodeMap = nodes.stream().collect(Collectors.toMap(Element::getId, n -> n));

		final ConfigurationCenter config = (ConfigurationCenter) configurationCenter.clone();
		if (CollectionUtils.isNotEmpty(nodes)) {

			// Get merge table map
			Map<String, MergeTableNode> mergeTableMap = MergeTableUtil.getMergeTableMap(nodes, edges);

			for (Node node : nodes) {
				Connections connection = null;
				List<RelateDataBaseTable> nodeSchemas;
				DatabaseTypeEnum.DatabaseType databaseType = null;
				nodeSchemas = getNodeSchema(node.getId());
				if (CollectionUtils.isEmpty(nodeSchemas) && !(node instanceof CacheNode)) {
					if (needForceNodeSchema(subTaskDto)) {
						throw new NodeException(String.format("node [id %s, name %s] schema cannot be empty.", node.getId(), node.getName()));
					}
				}
				if (node.isDataNode()) {
					String connectionId = null;
					if (node instanceof DataNode) {
						connectionId = ((DataNode) node).getConnectionId();
					} else if (node instanceof DatabaseNode) {
						connectionId = ((DatabaseNode) node).getConnectionId();
					}
					connection = getConnection(connectionId);
					if (connection == null) {
						throw new NodeException(String.format("node [id %s, name %s] connection cannot be null.", node.getId(), node.getName()));
					}
					databaseType = ConnectionUtil.getDatabaseType(clientMongoOperator, connection.getPdkHash());

					if (connection != null && "pdk".equals(connection.getPdkType())) {
						PdkUtil.downloadPdkFileIfNeed((HttpClientMongoOperator) clientMongoOperator, databaseType.getPdkHash(), databaseType.getJarFile(), databaseType.getJarRid());
					}
				} else if (node instanceof CacheNode) {
					Optional<Edge> edge = edges.stream().filter(e -> e.getTarget().equals(node.getId())).findFirst();
					Node sourceNode = null;
					if (edge.isPresent()) {
						sourceNode = nodeMap.get(edge.get().getSource());
						if (sourceNode instanceof TableNode) {
							connection = getConnection(((TableNode) sourceNode).getConnectionId());
						}
					}
					messageDao.registerCache((CacheNode) node, (TableNode) sourceNode, connection, subTaskDto, clientMongoOperator);
				}
				TapTableMap<String, TapTable> tapTableMap = TapTableUtil.getTapTableMapByNodeId(node.getId());
				List<Node> predecessors = node.predecessors();
				List<Node> successors = node.successors();
				Connections finalConnection = connection;
				DatabaseTypeEnum.DatabaseType finalDatabaseType = databaseType;

				Vertex vertex = new Vertex(NodeUtil.getVertexName(node), () -> {
					try {
						Log4jUtil.setThreadContext(subTaskDto);
						return createNode(
								subTaskDto,
								nodes,
								edges,
								node,
								predecessors,
								successors,
								config,
								finalConnection,
								finalDatabaseType,
								mergeTableMap,
								tapTableMap
						);
					} catch (Exception e) {
						logger.error("create dag node failed: {}", e.getMessage(), e);
						throw e;
					}
				});
				vertexMap.put(node.getId(), vertex);

				vertex.localParallelism(1);
				dag.vertex(vertex);
			}

			handleEdge(dag, edges, nodeMap, vertexMap);
		}

		return new JetDag(dag, hazelcastBaseNodeMap, typeConvertMap);
	}

	private boolean needForceNodeSchema(SubTaskDto subTaskDto) {
		if (subTaskDto.getParentTask().getSyncType().equals("logCollector")) {
			return false;
		}
		return true;
	}

	public static HazelcastBaseNode createNode(
			SubTaskDto subTaskDto,
			List<Node> nodes,
			List<Edge> edges,
			Node node,
			List<Node> predecessors,
			List<Node> successors,
			ConfigurationCenter config,
			/*List<RelateDataBaseTable> nodeSchemas,*/
			Connections connection,
			DatabaseTypeEnum.DatabaseType databaseType,
			Map<String, MergeTableNode> mergeTableMap,
			TapTableMap<String, TapTable> tapTableMap
	) throws Exception {
		List<RelateDataBaseTable> nodeSchemas = new ArrayList<>();
		HazelcastBaseNode hazelcastNode;
		final String type = node.getType();
		final NodeTypeEnum nodeTypeEnum = NodeTypeEnum.get(type);
		switch (nodeTypeEnum) {
			case DATABASE:
			case TABLE:
				if (CollectionUtils.isNotEmpty(predecessors) && CollectionUtils.isNotEmpty(successors)) {
					if ("pdk".equals(connection.getPdkType())) {
						hazelcastNode = new HazelcastPdkSourceAndTargetTableNode(
								DataProcessorContext.newBuilder()
										.withSubTaskDto(subTaskDto)
										.withNode(node)
										.withNodes(nodes)
										.withEdges(edges)
										.withConfigurationCenter(config)
										.withConnectionConfig(connection.getConfig())
										.withDatabaseType(databaseType)
										.withTapTableMap(tapTableMap)
										.build()
						);
					} else {
						hazelcastNode = new HazelcastTaskSourceAndTarget(
								DataProcessorContext.newBuilder()
										.withSubTaskDto(subTaskDto)
										.withNode(node)
										.withNodes(nodes)
										.withEdges(edges)
										.withConfigurationCenter(config)
										.withConnections(connection)
										.withCacheService(cacheService)
										.build());
					}
				} else if (CollectionUtils.isNotEmpty(successors)) {
					if ("pdk".equals(connection.getPdkType())) {
						hazelcastNode = new HazelcastSourcePdkDataNode(
								DataProcessorContext.newBuilder()
										.withSubTaskDto(subTaskDto)
										.withNode(node)
										.withNodes(nodes)
										.withEdges(edges)
										.withConfigurationCenter(config)
										.withConnectionConfig(connection.getConfig())
										.withDatabaseType(databaseType)
										.withTapTableMap(tapTableMap)
										.build());
					} else {
						hazelcastNode = new HazelcastTaskSource(
								DataProcessorContext.newBuilder()
										.withSubTaskDto(subTaskDto)
										.withNode(node)
										.withNodes(nodes)
										.withEdges(edges)
										.withConfigurationCenter(config)
										.withSourceConn(connection)
										.build());
					}
				} else {
					if ("pdk".equals(connection.getPdkType())) {
						hazelcastNode = new HazelcastTargetPdkDataNode(
								DataProcessorContext.newBuilder()
										.withSubTaskDto(subTaskDto)
										.withNode(node)
										.withNodes(nodes)
										.withEdges(edges)
										.withConfigurationCenter(config)
										.withConnectionConfig(connection.getConfig())
										.withDatabaseType(databaseType)
										.withTapTableMap(tapTableMap)
										.build());
					} else {
						hazelcastNode = new HazelcastTaskTarget(
								DataProcessorContext.newBuilder()
										.withSubTaskDto(subTaskDto)
										.withNode(node)
										.withNodes(nodes)
										.withEdges(edges)
										.withConfigurationCenter(config)
										.withNodeSchemas(nodeSchemas)
										.withTargetConn(connection)
										.withCacheService(cacheService)
										.build()
						);
					}
				}
				break;
			case CACHE:
				hazelcastNode = new HazelcastCacheTarget(
						DataProcessorContext.newBuilder()
								.withSubTaskDto(subTaskDto)
								.withNode(node)
								.withNodes(nodes)
								.withEdges(edges)
								.withConfigurationCenter(config)
								.withTargetConn(connection)
								.withCacheService(cacheService)
								.withTapTableMap(tapTableMap)
								.build()
				);
				break;
			case JOIN:
				hazelcastNode = new HazelcastJoinProcessor(
						ProcessorBaseContext.newBuilder()
								.withSubTaskDto(subTaskDto)
								.withNode(node)
								.withNodeSchemas(nodeSchemas)
								.withTapTableMap(tapTableMap)
								.build()
				);
				break;
			case JS_PROCESSOR:
			case FIELD_PROCESSOR:
			case ROW_FILTER_PROCESSOR:
			case CACHE_LOOKUP_PROCESSOR:
			case FIELD_RENAME_PROCESSOR:
			case FIELD_MOD_TYPE_PROCESSOR:
			case FIELD_CALC_PROCESSOR:
			case FIELD_ADD_DEL_PROCESSOR:
				hazelcastNode = new HazelcastProcessorNode(
						DataProcessorContext.newBuilder()
								.withSubTaskDto(subTaskDto)
								.withNode(node)
								.withCacheService(cacheService)
								.withConfigurationCenter(config)
								.withTapTableMap(tapTableMap)
								.build()
				);
				break;
//            case LOG_COLLECTOR:
//                hazelcastNode = new HazelcastLogCollectSource(
//                    DataProcessorContext.newBuilder()
//                        .withSubTaskDto(subTaskDto)
//                        .withNode(node)
//                        .withNodes(nodes)
//                        .withEdges(edges)
//                        .withConfigurationCenter(config)
//                        .build()
//                );
//                break;
//            case HAZELCASTIMDG:
//                Connections connections = new Connections();
//                connections.setDatabase_type(DatabaseTypeEnum.HAZELCAST_IMDG.getType());
//                hazelcastNode = new HazelcastTaskTarget(
//                    DataProcessorContext.newBuilder()
//                        .withSubTaskDto(subTaskDto)
//                        .withNode(node)
//                        .withNodes(nodes)
//                        .withEdges(edges)
//                        .withConfigurationCenter(config)
//                        .withTargetConn(connections)
//                        .withCacheService(cacheService)
//                        .build()
//                );
//                break;
			case CUSTOM_PROCESSOR:
				hazelcastNode = new HazelcastCustomProcessor(
						DataProcessorContext.newBuilder()
								.withSubTaskDto(subTaskDto)
								.withNode(node)
								.withConfigurationCenter(config)
								.withTapTableMap(tapTableMap)
								.build()
				);
				break;
			case MERGETABLE:
				hazelcastNode = new HazelcastMergeNode(
						DataProcessorContext.newBuilder()
								.withSubTaskDto(subTaskDto)
								.withNode(node)
								.withNodes(nodes)
								.withEdges(edges)
								.withConfigurationCenter(config)
								.withTapTableMap(tapTableMap)
								.build()
				);
				break;
			default:
				hazelcastNode = new HazelcastBlank(
						DataProcessorContext.newBuilder()
								.withSubTaskDto(subTaskDto)
								.withNode(node)
								.build()
				);
				break;
		}
		MergeTableUtil.setMergeTableIntoHZTarget(mergeTableMap, hazelcastNode);
		return hazelcastNode;
	}

	private boolean needAddTypeConverter(SubTaskDto subTaskDto) {
		if (subTaskDto.getParentTask().getSyncType().equals("logCollector")) {
			return false;
		}
		return true;
	}

	private void handleEdge(
			DAG dag,
			List<Edge> edges,
			Map<String, Node<?>> nodeMap,
			Map<String, Vertex> vertexMap
	) {
		if (CollectionUtils.isNotEmpty(edges)) {
			for (Edge edge : edges) {
				final String source = edge.getSource();
				final String target = edge.getTarget();
				final Node<?> srcNode = nodeMap.get(source);
				final Node<?> tgtNode = nodeMap.get(target);
				List<com.hazelcast.jet.core.Edge> outboundEdges = dag.getOutboundEdges(NodeUtil.getVertexName(srcNode));
				List<com.hazelcast.jet.core.Edge> inboundEdges = dag.getInboundEdges(NodeUtil.getVertexName(tgtNode));
				dag.edge(
						com.hazelcast.jet.core.Edge
								.from(vertexMap.get(source), outboundEdges.size())
								.to(vertexMap.get(target), inboundEdges.size())
				);
			}
		}
	}

	@Override
	public TaskClient getTaskClient(String taskId) {
		return null;
	}

	@Override
	public List<TaskClient<SubTaskDto>> getTaskClients() {
		return null;
	}

	public List<RelateDataBaseTable> getNodeSchema(String nodeId) {
		final List<RelateDataBaseTable> nodeSchemas = clientMongoOperator.find(
				new Query(where("nodeId").is(nodeId)),
				ConnectorConstant.METADATA_INSTANCE_COLLECTION + "/node/oldSchema",
				RelateDataBaseTable.class
		);

		return nodeSchemas;
	}

	public Map<String, String> getTableNameQualifiedNameMap(String nodeId) {
		return clientMongoOperator
				.findOne(Query.query(where("nodeId").is(nodeId)),
						ConnectorConstant.METADATA_INSTANCE_COLLECTION + "/node/tableMap", Map.class);
	}

	private Connections getConnection(String connectionId) {
		final Connections connections = clientMongoOperator.findOne(
				new Query(where("_id").is(connectionId)),
				ConnectorConstant.CONNECTION_COLLECTION,
				Connections.class
		);
		connections.decodeDatabasePassword();
		connections.initCustomTimeZone();
		return connections;
	}

	private MilestoneFlowServiceJetV2 initMilestone(SubTaskDto subTaskDto) {
		if (null == subTaskDto) {
			throw new IllegalArgumentException("Input parameter subTaskDto,dag cannot be empty");
		}

		// 初始化dag里面每条连线的里程碑
		List<Node> nodes = subTaskDto.getDag().getNodes();
		HttpClientMongoOperator httpClientMongoOperator = (HttpClientMongoOperator) clientMongoOperator;

		MilestoneFlowServiceJetV2 jetMilestoneService = MilestoneFactory.getJetMilestoneService(subTaskDto, httpClientMongoOperator.getRestTemplateOperator().getBaseURLs(),
				httpClientMongoOperator.getRestTemplateOperator().getRetryTime(), httpClientMongoOperator.getConfigCenter());

		List<Node> dataNodes = nodes.stream().filter(n -> n.isDataNode() || n instanceof DatabaseNode).collect(Collectors.toList());

		for (Node<?> node : dataNodes) {
			String sourceVertexName = NodeUtil.getVertexName(node);
			List<Node<?>> successors = GraphUtil.successors(node, Node::isDataNode);
			for (Node<?> successor : successors) {
				String destVertexName = NodeUtil.getVertexName(successor);
				MilestoneContext taskMilestoneContext = jetMilestoneService.getMilestoneContext();
				MilestoneJetEdgeService jetEdgeMilestoneService = MilestoneFactory.getJetEdgeMilestoneService(
						subTaskDto,
						httpClientMongoOperator.getRestTemplateOperator().getBaseURLs(),
						httpClientMongoOperator.getRestTemplateOperator().getRetryTime(),
						httpClientMongoOperator.getConfigCenter(),
						node,
						successor,
						sourceVertexName,
						destVertexName,
						taskMilestoneContext
				);

				List<Milestone> milestones = jetEdgeMilestoneService.initMilestones();
				jetEdgeMilestoneService.updateList(milestones);
			}
		}

		// 初始化并更新整个SubTask的里程碑
		jetMilestoneService.updateList();

		return jetMilestoneService;
	}
}
