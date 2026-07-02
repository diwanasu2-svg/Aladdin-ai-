import time
from fastapi import APIRouter
from pydantic import BaseModel

class MetricsCollector:
    def __init__(self):
        self.request_count = 0
        self.error_count = 0
        self.latencies = []

    def record_request(self):
        self.request_count += 1

    def record_error(self):
        self.error_count += 1

    def record_latency(self, latency: float):
        self.latencies.append(latency)
        # Keep only the last 1000 to prevent unbounded growth
        if len(self.latencies) > 1000:
            self.latencies.pop(0)

    def get_p99_latency(self) -> float:
        if not self.latencies:
            return 0.0
        sorted_latencies = sorted(self.latencies)
        index = int(len(sorted_latencies) * 0.99)
        # Handle case where index might be out of bounds for very small lists
        index = min(index, len(sorted_latencies) - 1)
        return sorted_latencies[index]

    def get_metrics(self):
        return {
            "request_count": self.request_count,
            "error_count": self.error_count,
            "latency_p99_ms": round(self.get_p99_latency() * 1000, 2) # convert to ms
        }

metrics_collector = MetricsCollector()

router = APIRouter()

@router.get("/metrics")
async def get_metrics():
    return metrics_collector.get_metrics()

# Example decorator/middleware usage (conceptual)
# @app.middleware("http")
# async def metrics_middleware(request, call_next):
#     start_time = time.perf_counter()
#     metrics_collector.record_request()
#     try:
#         response = await call_next(request)
#         if response.status_code >= 400:
#             metrics_collector.record_error()
#         return response
#     except Exception as e:
#         metrics_collector.record_error()
#         raise e
#     finally:
#         process_time = time.perf_counter() - start_time
#         metrics_collector.record_latency(process_time)
