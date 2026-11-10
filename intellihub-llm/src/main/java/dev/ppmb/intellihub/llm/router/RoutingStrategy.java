package dev.ppmb.intellihub.llm.router;

/**
 * 多模型高可用路由与负载均衡策略枚举。
 */
public enum RoutingStrategy {

    /**
     * 主备链式故障转移（Priority Failover）：始终优先请求列表中的第一个健康节点，当发生限流（429）、超时或服务端 5xx
     * 异常时，按顺序无缝降级到下一个候选节点。
     */
    FAILOVER,

    /**
     * 轮询负载均衡（Round-Robin + Failover）：在所有健康候选节点间均匀轮询分发请求；若当前选中的节点调用失败，自动向其余健康节点故障转移。
     */
    ROUND_ROBIN,

    /**
     * 加权负载均衡（Weighted + Failover）：按各候选节点配置的权重（{@code weight}）比例分配请求流量；若命中节点调用失败，自动向其余健康节点故障转移。
     */
    WEIGHTED
}
