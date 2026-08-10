#!/usr/bin/env python3
import requests
import time
from datetime import datetime, timedelta
from collections import defaultdict

ENDPOINTS = {
    "intake-orchestrator": "https://legal-gate-intake-orchestration.onrender.com/actuator/health",
    "gateway": "https://legal-gate-gateway.onrender.com/actuator/health",
    "mail-ingress": "https://legal-gate-mail-ingress.onrender.com/actuator/health",
    "consultation-classifier": "https://legal-gate-consultation-classifier.onrender.com/health",
}

class ServiceMonitor:
    def __init__(self):
        self.services = {name: {
            "status": None,
            "last_change": datetime.now(),
            "uptime_total": timedelta(0),
            "downtime_total": timedelta(0),
            "last_check": None,
        } for name in ENDPOINTS}
        self.start_time = datetime.now()

    def check_health(self, name, url):
        try:
            response = requests.get(url, timeout=5)
            status = response.status_code == 200 and response.json().get("status") in ("UP", "up")
            return "UP" if status else "DOWN"
        except Exception:
            return "DOWN"

    def update_service(self, name, current_status):
        service = self.services[name]
        prev_status = service["status"]
        now = datetime.now()

        if prev_status and prev_status != current_status:
            duration = now - service["last_change"]
            if prev_status == "UP":
                service["uptime_total"] += duration
            else:
                service["downtime_total"] += duration
            service["last_change"] = now

        service["status"] = current_status
        service["last_check"] = now

    def format_duration(self, td):
        total_seconds = int(td.total_seconds())
        hours = total_seconds // 3600
        mins = (total_seconds % 3600) // 60
        secs = total_seconds % 60
        return f"{hours:02d}:{mins:02d}:{secs:02d}"

    def get_current_streak(self, name):
        service = self.services[name]
        if not service["last_change"]:
            return timedelta(0)
        return datetime.now() - service["last_change"]

    def display(self):
        print("\033[2J\033[H", end="")  # Clear screen
        runtime = datetime.now() - self.start_time
        print(f"{'='*80}")
        print(f"Service Health Monitor | Running for {self.format_duration(runtime)}")
        print(f"{'='*80}\n")

        for name, url in ENDPOINTS.items():
            service = self.services[name]
            status = service["status"]
            status_color = "\033[92m" if status == "UP" else "\033[91m"
            reset = "\033[0m"

            streak = self.get_current_streak(name)
            total_up = service["uptime_total"] + (streak if status == "UP" else timedelta(0))
            total_down = service["downtime_total"] + (streak if status == "DOWN" else timedelta(0))

            print(f"{name:30} {status_color}{status:^6}{reset} (streak: {self.format_duration(streak)})")
            print(f"  ↑ {self.format_duration(total_up)}  ↓ {self.format_duration(total_down)}")
            if service["last_check"]:
                print(f"  Last check: {service['last_check'].strftime('%H:%M:%S')}")
            print()

    def run(self, interval=30):
        try:
            while True:
                for name, url in ENDPOINTS.items():
                    status = self.check_health(name, url)
                    self.update_service(name, status)

                self.display()
                time.sleep(interval)
        except KeyboardInterrupt:
            print("\n\nMonitor stopped.")

if __name__ == "__main__":
    monitor = ServiceMonitor()
    monitor.run(interval=20)
