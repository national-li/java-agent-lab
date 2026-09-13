-- 初始化脚本：只在 Postgres 数据卷为空时（首次启动）自动执行一次
-- 如果需要重新执行：docker compose down -v 后再 up

-- W4 的 RAG / 长期记忆需要 vector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 顺便开一个常用扩展，后面做混合检索/全文检索会用到
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 验证：启动后可以查一下扩展是否装上
--   docker exec -it agent-postgres psql -U agent -d agentdb -c "\dx"
