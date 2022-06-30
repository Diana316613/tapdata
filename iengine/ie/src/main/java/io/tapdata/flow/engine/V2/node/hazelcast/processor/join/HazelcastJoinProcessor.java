package io.tapdata.flow.engine.V2.node.hazelcast.processor.join;

import com.tapdata.constant.MapUtil;
import com.tapdata.entity.MessageEntity;
import com.tapdata.entity.OperationType;
import com.tapdata.entity.task.context.ProcessorBaseContext;
import com.tapdata.tm.commons.dag.Node;
import com.tapdata.tm.commons.dag.process.JoinProcessorNode;
import io.tapdata.common.sample.sampler.CounterSampler;
import io.tapdata.common.sample.sampler.ResetCounterSampler;
import io.tapdata.common.sample.sampler.SpeedSampler;
import io.tapdata.constructImpl.ConstructIMap;
import io.tapdata.entity.event.dml.TapDeleteRecordEvent;
import io.tapdata.entity.event.dml.TapInsertRecordEvent;
import io.tapdata.entity.event.dml.TapRecordEvent;
import io.tapdata.entity.event.dml.TapUpdateRecordEvent;
import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapIndex;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.flow.engine.V2.entity.TapdataEvent;
import io.tapdata.flow.engine.V2.exception.node.NodeException;
import io.tapdata.flow.engine.V2.node.hazelcast.HazelcastBaseNode;
import io.tapdata.flow.engine.V2.util.TapEventUtil;
import io.tapdata.metrics.TaskSampleRetriever;
import io.tapdata.schema.TapTableMap;
import lombok.SneakyThrows;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.stream.Collectors;

import static io.tapdata.flow.engine.V2.node.hazelcast.processor.join.HazelcastJoinProcessor.JoinOperation.Delete;
import static io.tapdata.flow.engine.V2.node.hazelcast.processor.join.HazelcastJoinProcessor.JoinOperation.Upsert;

/**
 * @author jackin
 * @date 2021/12/15 12:08 PM
 **/
public class HazelcastJoinProcessor extends HazelcastBaseNode {

	private final Logger logger = LogManager.getLogger(HazelcastJoinProcessor.class);

	private final JoinType joinType;

	private final static String IMAP_NAME_DELIMITER = "-";

	private ConstructIMap<Map<String, Map<String, Object>>> leftJoinCache;
	private ConstructIMap<Map<String, Map<String, Object>>> rightJoinCache;

//  private List<String> keyFields;

	private List<String> leftJoinKeyFields;
	private List<String> rightJoinKeyFields;
	private String embeddedPath;
	private String leftNodeId;
	private String rightNodeId;
	private Context context;

	private List<String> leftPrimaryKeys;
	private List<String> rightPrimaryKeys;
	// statistic and sample related

	private ResetCounterSampler resetInputCounter;
	private CounterSampler inputCounter;
	private ResetCounterSampler resetOutputCounter;
	private CounterSampler outputCounter;
	private SpeedSampler inputQPS;
	private SpeedSampler outputQPS;
	private TapTable tapTable;

	public HazelcastJoinProcessor(ProcessorBaseContext processorBaseContext) {
		super(processorBaseContext);
		Node<?> node = processorBaseContext.getNode();
		TapTableMap<String, TapTable> tapTableMap = processorBaseContext.getTapTableMap();
		Iterator<String> iterator = tapTableMap.keySet().iterator();
		if (iterator.hasNext()) {
			String next = iterator.next();
			tapTable = tapTableMap.get(next);
		} else {
			throw new RuntimeException("Cannot find join node's schema");
		}

		vatidate(node);

		JoinProcessorNode joinNode = (JoinProcessorNode) node;
		this.joinType = JoinType.get(joinNode.getJoinType());

		final Boolean embeddedMode = joinNode.getEmbeddedMode();
		if (embeddedMode) {
			final JoinProcessorNode.EmbeddedSetting embeddedSetting = joinNode.getEmbeddedSetting();
			this.embeddedPath = embeddedSetting.getPath();
		}
		final List<JoinProcessorNode.JoinExpression> joinExpressions = joinNode.getJoinExpressions();
		this.leftJoinKeyFields = new ArrayList<>(joinExpressions.size());
		this.rightJoinKeyFields = new ArrayList<>(joinExpressions.size());
		for (JoinProcessorNode.JoinExpression joinExpression : joinExpressions) {
			final String left = joinExpression.getLeft();
			final String right = joinExpression.getRight();
			leftJoinKeyFields.add(left);
			rightJoinKeyFields.add(right);
		}

		this.leftNodeId = joinNode.getLeftNodeId();
		this.rightNodeId = joinNode.getRightNodeId();

//    this.leftPrimaryKeys = joinNode.getLeftPrimaryKeys();
//    this.rightPrimaryKeys = joinNode.getRightPrimaryKeys();

		this.leftPrimaryKeys = joinNode.getLeftPrimaryKeys();
		this.rightPrimaryKeys = joinNode.getRightPrimaryKeys();
		pkChecker();

//    final List<RelateDatabaseField> fields = nodeSchemas.get(0).getFields();
//
//    Collections.sort(
//      fields,
//      Comparator.comparingInt(RelateDatabaseField::getPrimary_key_position)
//    );
//    keyFields = new ArrayList<>(fields.size());
//    for (RelateDatabaseField field : fields) {
//      if (field.getPrimary_key_position() > 0) {
//        keyFields.add(field.getField_name());
//      }
//    }

	}

	private void pkChecker() {
		if (CollectionUtils.isEmpty(leftPrimaryKeys)) {
			throw new NodeException("Join processor missing left table primary key");
		}
		if (CollectionUtils.isEmpty(rightPrimaryKeys)) {
			throw new NodeException("Join processor missing right table primary key");
		}
	}

	@Override
	public void init(@Nonnull Context context) throws Exception {
//    this.leftJoinCache = context.hazelcastInstance().getMap("leftJoinCache");
		super.init(context);
		this.leftJoinCache = new ConstructIMap<>(
				context.hazelcastInstance(),
				joinCacheMapName(leftNodeId, "leftJoinCache")
		);
		this.rightJoinCache = new ConstructIMap<>(
				context.hazelcastInstance(),
				joinCacheMapName(rightNodeId, "rightCache")
		);
		this.context = context;
		if (!taskHasBeenRun()) {
			leftJoinCache.clear();
			rightJoinCache.clear();
		}
	}

	@Override
	protected void initSampleCollector() {
		super.initSampleCollector();

		// TODO: init outputCounter initial value
		Map<String, Number> values = TaskSampleRetriever.getInstance().retrieve(tags, Arrays.asList(
				"inputTotal", "outputTotal"
		));
		// init statistic and sample related initialize
		resetInputCounter = statisticCollector.getResetCounterSampler("inputTotal");
		inputCounter = sampleCollector.getCounterSampler("inputTotal", values.getOrDefault("inputTotal", 0).longValue());
		resetOutputCounter = statisticCollector.getResetCounterSampler("outputTotal");
		outputCounter = sampleCollector.getCounterSampler("outputTotal", values.getOrDefault("outputTotal", 0).longValue());
		inputQPS = sampleCollector.getSpeedSampler("inputQPS");
		outputQPS = sampleCollector.getSpeedSampler("outputQPS");
	}


	@Override
	public boolean tryProcess(int ordinal, @Nonnull Object item) {
		Node<?> node = processorBaseContext.getNode();
		if (logger.isDebugEnabled()) {
			logger.debug(
					"join node [id: {}, name: {}] receive event {}",
					node.getId(),
					node.getName(),
					item
			);
		}
		TapdataEvent tapdataEvent = (TapdataEvent) item;

		Map<String, Object> before;
		Map<String, Object> after;
		String opType;
		String tableName;
		if (null != tapdataEvent.getMessageEntity()) {
			MessageEntity messageEntity = tapdataEvent.getMessageEntity();
			after = messageEntity.getAfter();
			before = messageEntity.getBefore();
			tableName = messageEntity.getTableName();
			final OperationType operationType = OperationType.fromOp(messageEntity.getOp());
			opType = operationType.getOp();
		} else if (null != tapdataEvent.getTapEvent()) {
			transformFromTapValue(tapdataEvent, null);
			TapRecordEvent dataEvent = (TapRecordEvent) tapdataEvent.getTapEvent();
			before = TapEventUtil.getBefore(dataEvent);
			after = TapEventUtil.getAfter(dataEvent);
			opType = TapEventUtil.getOp(dataEvent);
			tableName = dataEvent.getTableId();
		} else {
			while (running.get()) {
				if (offer(tapdataEvent)) break;
			}
			return true;
		}

		if (opType == null) {
			tapdataEvent.setFromNodeId(node.getId());
			tapdataEvent.addNodeId(node.getId());
			return offer(tapdataEvent);
		}

		List<JoinResult> joinResults;
		if (leftNodeId.equals(tapdataEvent.getNodeIds().get(0))) {
			joinResults = leftJoinLeftProcess(before, after, opType);
		} else {
			joinResults = leftJoinRightProcess(before, after, opType);
		}

		if (CollectionUtils.isNotEmpty(joinResults)) {
			List<TapdataEvent> tapdataEvents = new ArrayList<>();
			for (JoinResult joinResult : joinResults) {
				TapRecordEvent joinEvent = joinResult2DataEvent(tableName, joinResult);
				if (OperationType.INSERT.getOp().equals(TapEventUtil.getOp(joinEvent))) {
					final TapRecordEvent deleteEvent = generateDeleteEventForModifyJoinKey(joinEvent);
					if (deleteEvent != null) {
						TapdataEvent deleteTapdataEvent = new TapdataEvent();
						deleteTapdataEvent.setTapEvent(deleteEvent);
						deleteTapdataEvent.setSyncStage(tapdataEvent.getSyncStage());
						tapdataEvents.add(deleteTapdataEvent);
					}
				}

				TapdataEvent joinTapdataEvent = new TapdataEvent();
				joinTapdataEvent.setTapEvent(joinEvent);
				joinTapdataEvent.setSyncStage(tapdataEvent.getSyncStage());
				TapTableMap<String, TapTable> tapTableMap = processorBaseContext.getTapTableMap();
				transformToTapValue(joinTapdataEvent, tapTableMap, processorBaseContext.getNode().getId());
				tapdataEvents.add(joinTapdataEvent);
			}
			if (CollectionUtils.isNotEmpty(tapdataEvents)) {
				if (logger.isDebugEnabled()) {
					logger.debug("join node [id: {}, name: {}] join results {}", node.getId(), node.getName(), tapdataEvents);
				}

				for (TapdataEvent event : tapdataEvents) {
					while (running.get()) {
						if (offer(event)) {
							break;
						}
					}
				}
			}
		}

		return true;
	}

	@Override
	public void close() throws Exception {
		super.close();
	}

	private void vatidate(Node<?> node) {
		List<TapField> pks = tapTable.getNameFieldMap().values().stream()
				.filter(f -> null != f.getPrimaryKeyPos() && f.getPrimaryKeyPos() > 0)
				.collect(Collectors.toList());
		if (CollectionUtils.isNotEmpty(pks)) {
			throw new NodeException(
					String.format(
							"left join node [id: %s, node name: %s] schema cannot contain primary key(s), pk fields %s",
							node.getId(),
							node.getName(),
							String.join(",", tapTable.primaryKeys())
					)
			);
		}

		List<TapIndex> indexList = tapTable.getIndexList();
		if (null == indexList) {
			throw new NodeException(
					String.format(
							"left join node [id: %s, node name: %s] does not contain unique index",
							node.getId(),
							node.getName()
					)
			);
		}
		TapIndex uniqueIndex = indexList.stream().filter(TapIndex::isUnique).findFirst().orElse(null);
		if (null == uniqueIndex) {
			throw new NodeException(
					String.format(
							"left join node [id: %s, node name: %s] does not contain unique index",
							node.getId(),
							node.getName()
					)
			);
		}
	}

	/**
	 * If the right row pk was modified in join result, the downstream cannot guarantee write idempotent.
	 * So generate delete event, before join event.
	 *
	 * @param joinEvent
	 * @return delete dml event, if null：the join key value has not been modified。
	 */
	private TapRecordEvent generateDeleteEventForModifyJoinKey(TapRecordEvent joinEvent) {
		TapRecordEvent deleteEvent = null;
		Map<String, Object> before = TapEventUtil.getBefore(joinEvent);
		Map<String, Object> after = TapEventUtil.getAfter(joinEvent);
		if (MapUtils.isNotEmpty(before) && MapUtils.isNotEmpty(after)) {
			final String beforeJoinKey = project(before, rightPrimaryKeys);
			final String afterJoinKey = project(after, rightPrimaryKeys);

			if (!StringUtils.equals(beforeJoinKey, afterJoinKey)) {
				deleteEvent = new TapDeleteRecordEvent();
				TapEventUtil.setBefore(deleteEvent, TapEventUtil.getBefore(joinEvent));
				deleteEvent.setTableId(joinEvent.getTableId());
				deleteEvent.setTime(System.currentTimeMillis());

				if (logger.isDebugEnabled()) {
					logger.debug("join key has been modified, will generate delete event before join event, delete event {}, join event {}.", deleteEvent, joinEvent);
				}
			}
		}

		return deleteEvent;
	}

	private String joinCacheMapName(String leftNodeId, String name) {
		return leftNodeId + IMAP_NAME_DELIMITER + name;
	}

	private TapRecordEvent joinResult2DataEvent(String tableName, JoinResult joinResult) {

		TapRecordEvent tapRecordEvent = null;
		String opType = joinResult.getOpType();
		OperationType operationType = OperationType.fromOp(opType);
		switch (operationType) {
			case INSERT:
				tapRecordEvent = new TapInsertRecordEvent();
				TapEventUtil.setAfter(tapRecordEvent, joinResult.getAfter());
				break;
			case UPDATE:
				tapRecordEvent = new TapUpdateRecordEvent();
				TapEventUtil.setBefore(tapRecordEvent, joinResult.getBefore());
				TapEventUtil.setAfter(tapRecordEvent, joinResult.getAfter());
				break;
			case DELETE:
				tapRecordEvent = new TapDeleteRecordEvent();
				TapEventUtil.setBefore(tapRecordEvent, joinResult.getBefore());
				break;
			default:
//        tapRecordEvent = new TapRecordEvent();
				break;
		}
		tapRecordEvent.setTableId(tableName);
		tapRecordEvent.setTime(System.currentTimeMillis());

		return tapRecordEvent;
	}

	@SneakyThrows
	private List<JoinResult> leftJoinLeftProcess(
			Map<String, Object> before,
			Map<String, Object> after,
			String opType
	) {
		String beforeJoinKey = null;
		String beforeLeftKey = null;
		Map<String, Object> beforeLeftRow = null;
		String afterJoinKey = null;
		String afterLeftKey = null;
		Map<String, Object> afterLeftRow = null;
		OperationType operationType = OperationType.fromOp(opType);
		switch (operationType) {
			case INSERT:
			case UPDATE:
				afterLeftRow = after;
				afterLeftKey = project(afterLeftRow, leftPrimaryKeys);
				afterJoinKey = project(afterLeftRow, leftJoinKeyFields);
				if (MapUtils.isNotEmpty(before)) {
					beforeLeftRow = before;
					beforeLeftKey = project(before, leftPrimaryKeys);
					beforeJoinKey = project(before, leftJoinKeyFields);
				}
				break;
			case DELETE:
				beforeLeftRow = before;
				beforeLeftKey = project(beforeLeftRow, leftPrimaryKeys);
				beforeJoinKey = project(beforeLeftRow, leftJoinKeyFields);
				break;
			default:
				return null;
		}

		if (!OperationType.DELETE.getOp().equals(opType) && !leftJoinCache.exists(afterJoinKey)) {
			Map<String, Map<String, Object>> leftKeyCache = new HashMap<>();
			leftKeyCache.put(afterLeftKey, afterLeftRow);
			leftJoinCache.insert(afterJoinKey, leftKeyCache);
		} else {
			String joinKey = afterJoinKey;
			String key = afterLeftKey;
			if (OperationType.DELETE.getOp().equals(opType)) {
				deleteRowFromCache(beforeJoinKey, beforeLeftKey, leftJoinCache);
			} else {
				final Map<String, Map<String, Object>> leftKeyCache = leftJoinCache.find(joinKey);
				if (MapUtils.isNotEmpty(beforeLeftRow) && leftKeyCache.containsKey(key)) {
					beforeLeftRow = leftKeyCache.get(key);
					beforeLeftKey = project(beforeLeftRow, leftPrimaryKeys);
					beforeJoinKey = project(beforeLeftRow, leftJoinKeyFields);
				}
				leftKeyCache.put(afterLeftKey, afterLeftRow);
				leftJoinCache.insert(afterJoinKey, leftKeyCache);
			}
		}

		return leftJoinLeftRow(afterJoinKey, afterLeftKey, afterLeftRow, beforeJoinKey, beforeLeftKey, beforeLeftRow, opType);
	}

	private void deleteRowFromCache(String joinKey, String key, ConstructIMap<Map<String, Map<String, Object>>> joinCache) throws Exception {
		final Map<String, Map<String, Object>> keyCache = joinCache.find(joinKey);
		String finalBeforeKey = key;
		Optional.ofNullable(keyCache).ifPresent(m -> m.remove(finalBeforeKey));
		if (MapUtils.isEmpty(keyCache)) {
			joinCache.delete(joinKey);
		} else {
			joinCache.update(joinKey, keyCache);
		}
	}

	@SneakyThrows
	private List<JoinResult> leftJoinRightProcess(
			Map<String, Object> before,
			Map<String, Object> after,
			String opType
	) {
		String beforeJoinKey = null;
		String beforeRightKey = null;
		Map<String, Object> beforeRightRow = null;
		String afterJoinKey = null;
		String afterRightKey = null;
		Map<String, Object> afterRightRow = null;
		JoinOperation joinOperation;
		OperationType operationType = OperationType.fromOp(opType);
		switch (operationType) {
			case INSERT:
			case UPDATE:
				afterRightRow = after;
				afterJoinKey = project(afterRightRow, rightJoinKeyFields);
				afterRightKey = project(afterRightRow, rightPrimaryKeys);
				if (MapUtils.isNotEmpty(before)) {
					beforeRightRow = before;
					beforeRightKey = project(before, rightPrimaryKeys);
					beforeJoinKey = project(before, rightJoinKeyFields);
				}
				joinOperation = Upsert;
				break;
			case DELETE:
				beforeRightRow = before;
				beforeJoinKey = project(beforeRightRow, rightJoinKeyFields);
				joinOperation = JoinOperation.Update;
				beforeRightKey = project(beforeRightRow, rightPrimaryKeys);
				break;
			default:
				return null;
		}

		if (!OperationType.DELETE.getOp().equals(opType) && !rightJoinCache.exists(afterJoinKey)) {
			Map<String, Map<String, Object>> rightKeyCache = new HashMap<>();
			rightKeyCache.put(afterRightKey, afterRightRow);
			rightJoinCache.insert(afterJoinKey, rightKeyCache);
		} else {
			if (OperationType.DELETE.getOp().equals(opType)) {
				deleteRowFromCache(beforeJoinKey, beforeRightKey, rightJoinCache);
			} else {
				final Map<String, Map<String, Object>> rightKeyCache = rightJoinCache.find(afterJoinKey);
				if (MapUtils.isEmpty(beforeRightRow) && rightKeyCache.containsKey(beforeRightKey)) {
					beforeRightRow = rightKeyCache.get(beforeRightKey);
					beforeRightKey = project(beforeRightRow, rightPrimaryKeys);
					beforeJoinKey = project(beforeRightRow, rightJoinKeyFields);
				}
				rightKeyCache.put(afterRightKey, afterRightRow);
				rightJoinCache.insert(afterJoinKey, rightKeyCache);
			}
		}

		return leftJoinRightRow(afterJoinKey, afterRightKey, afterRightRow, beforeJoinKey, beforeRightKey, beforeRightRow, joinOperation, opType);
	}

	@SneakyThrows
	private List<JoinResult> leftJoinLeftRow(
			String afterJoinKey,
			String afterLeftKey,
			Map<String, Object> afterLeftRow,
			String beforeJoinKey,
			String beforeLeftKey,
			Map<String, Object> beforeLeftRow,
			String opType
	) {
		List<JoinResult> joinResults = null;
		String joinKey = StringUtils.isNotBlank(afterJoinKey) ? afterJoinKey : beforeJoinKey;
		Map<String, Object> row = MapUtils.isNotEmpty(afterLeftRow) ? afterLeftRow : beforeLeftRow;

//    Map<String, Map<String, Object>> leftKeyCache = leftJoinCache.find(afterJoinKey);
		final Map<String, Map<String, Object>> rightKeyCache = rightJoinCache.find(joinKey);
		if (MapUtils.isNotEmpty(rightKeyCache)) {
			joinResults = new ArrayList<>(rightKeyCache.values().size());
			for (Map<String, Object> rightRow : rightKeyCache.values()) {
				final JoinResult joinResult = getJoinResult(beforeLeftRow, row, opType);
				join(rightRow, Upsert, joinResult.getBefore());
				join(rightRow, Upsert, joinResult.getAfter());
				joinResults.add(joinResult);
			}
		} else {
			final JoinResult joinResult = getJoinResult(beforeLeftRow, row, opType);
			leftJoinFillRightRow(joinResult.getBefore());
			leftJoinFillRightRow(joinResult.getAfter());
			joinResults = Arrays.asList(joinResult);
		}

		return joinResults;
	}

	@NotNull
	private JoinResult getJoinResult(Map<String, Object> beforeRow, Map<String, Object> afterRow, String opType) throws IllegalAccessException, InstantiationException {
		Map<String, Object> beforeJoinResult = null;
		if (MapUtils.isNotEmpty(beforeRow)) {
			beforeJoinResult = new HashMap<>();
			MapUtil.deepCloneMap(beforeRow, beforeJoinResult);
		}
		Map<String, Object> afterJoinResult = null;
		if (MapUtils.isNotEmpty(afterRow)) {
			afterJoinResult = new HashMap<>();
			MapUtil.deepCloneMap(afterRow, afterJoinResult);
		}
		return new JoinResult(beforeJoinResult, afterJoinResult, opType);
	}

	private void leftJoinFillRightRow(Map<String, Object> joinResult) {
		if (MapUtils.isNotEmpty(joinResult) && tapTable != null && MapUtils.isNotEmpty(tapTable.getNameFieldMap())) {
			LinkedHashMap<String, TapField> nameFieldMap = tapTable.getNameFieldMap();
			nameFieldMap.values().forEach(f -> {
				if (!f.getName().contains("\\.") && !joinResult.containsKey(f.getName())) {
					try {
						MapUtil.putValueInMap(joinResult, f.getName(), null);
					} catch (Exception e) {
						throw new NodeException(String.format("fill right row fields to null failed %s", e.getMessage()), e);
					}
				}
			});
		}
	}

	private List<JoinResult> leftJoinRightRow(
			String afterJoinKey,
			String afterRightKey,
			Map<String, Object> afterRightRow,
			String beforeJoinKey,
			String beforeRightKey,
			Map<String, Object> beforeRightRow,
			JoinOperation joinOperation,
			String opType
	) throws Exception {

		List<JoinResult> joinResults = null;
		String joinKey = StringUtils.isNotBlank(afterJoinKey) ? afterJoinKey : beforeJoinKey;

		if (leftJoinCache.exists(joinKey)) {
			final Map<String, Map<String, Object>> leftKeyCache = leftJoinCache.find(joinKey);
			joinResults = new ArrayList<>(leftKeyCache.size());
			for (Map<String, Object> beforeLeftRow : leftKeyCache.values()) {

				Map<String, Object> beforeJoinResult = new HashMap<>();
				MapUtil.deepCloneMap(beforeLeftRow, beforeJoinResult);
				if (MapUtils.isNotEmpty(beforeRightRow)) {
					join(beforeRightRow, Upsert, beforeJoinResult);
				}
				leftJoinFillRightRow(beforeJoinResult);

				Map<String, Object> afterJoinResult = new HashMap<>();
				if (OperationType.DELETE.getOp().equals(opType)) {
					MapUtil.deepCloneMap(beforeLeftRow, afterJoinResult);
					leftJoinFillRightRow(afterJoinResult);
				} else if (MapUtils.isNotEmpty(afterRightRow)) {
					MapUtil.deepCloneMap(beforeLeftRow, afterJoinResult);
					join(afterRightRow, Upsert, afterJoinResult);
				}

				final String joinOpType = dealWithLeftJoinRightRowOpType(
						beforeJoinResult,
						afterJoinResult,
						opType,
						joinKey
				);
				joinResults.add(
						new JoinResult(
								beforeJoinResult,
								afterJoinResult,
								joinOpType
						)
				);
			}
		}

		return joinResults;
	}

	/**
	 * deal the join result op type
	 *
	 * @param before
	 * @param after
	 * @param opType
	 * @return
	 */
	private String dealWithLeftJoinRightRowOpType(
			Map<String, Object> before,
			Map<String, Object> after,
			String opType,
			String joinKey
	) throws Exception {
		if (OperationType.DELETE.getOp().equals(opType)) {

			final Map<String, Map<String, Object>> rightKeyCache = rightJoinCache.find(joinKey);
			if (MapUtils.isNotEmpty(rightKeyCache)) {
				return OperationType.DELETE.getOp();
			} else {
				return OperationType.UPDATE.getOp();
			}
		}

		if (MapUtils.isNotEmpty(before) && MapUtils.isNotEmpty(after)) {
			final String beforeRightKey = project(before, rightPrimaryKeys);
			final String afterRightKey = project(after, rightPrimaryKeys);

			if (StringUtils.equals(beforeRightKey, afterRightKey)) {
				return OperationType.UPDATE.getOp();
			}
		}
		return OperationType.INSERT.getOp();
	}

	private void join(Map<String, Object> rightRow, JoinOperation joinOperation, Map<String, Object> leftRow) {
		if (leftRow == null) {
			return;
		}
		if (StringUtils.isNotBlank(embeddedPath)) {
			if (leftRow.containsKey(embeddedPath)) {
				final Object o = leftRow.get(embeddedPath);
				if (o instanceof Map) {
					final Map joinPathMap = (Map) leftRow.get(embeddedPath);
					if (joinOperation == Delete) {
						rightRow.keySet().forEach(k -> joinPathMap.put(k, null));
					} else {
						joinPathMap.putAll(rightRow);
					}
					return;
				}
			}

			if (joinOperation != Delete) {
				leftRow.put(embeddedPath, rightRow);
			}
		} else {
			if (joinOperation == Delete) {
				leftRow.keySet().removeIf(rightRow::containsKey);
			} else {
				leftRow.putAll(rightRow);
			}
		}
	}

	public static String project(Map<String, Object> record, List<String> fields) {
		Object[] key = new Object[fields.size()];
		for (int i = 0; i < fields.size(); i++) {

			key[i] = record.get(fields.get(i));
		}
		return Arrays.deepToString(key);
	}

	public class JoinResult {
		private Map<String, Object> before;
		private Map<String, Object> after;
		private String opType;

		public JoinResult(Map<String, Object> before, Map<String, Object> after, String opType) {
			this.before = before;
			this.after = after;
			this.opType = opType;
		}

		public Map<String, Object> getBefore() {
			return before;
		}

		public Map<String, Object> getAfter() {
			return after;
		}

		public String getOpType() {
			return opType;
		}
	}

	public enum JoinOperation {
		Insert,
		Update,
		Upsert,
		Delete
	}
}