#!/usr/bin/env python3
"""Verifica infraestructura real del Paso 09 sin tocar el proyecto Compose anterior."""
import argparse
import base64
from datetime import datetime
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "target/step09"
EVIDENCE = ROOT.parent / "evidencias/EFT/09_empaquetado_configuracion.txt"
PROJECT = "eft_s9_validacion"
COMPOSE = ["docker", "compose", "-p", PROJECT, "-f", "docker-compose.yml", "-f", "docker-compose.validacion.yml"]
PORTS = {"config-server": 18888, "eureka-server": 18761, "authorization-server": 19000,
         "bff-web-service": 18082, "bff-mobile-service": 18083, "bff-atm-service": 18084}
INTERNAL = {"core-banking-service": 8081, "payment-service": 8085, "customer-service": 8086,
            "bff-web-service": 8082, "bff-mobile-service": 8083, "bff-atm-service": 8084}
MAIN_CLASSES = {"authorization-server": "authorization.AuthorizationServerApplication",
                "config-server": "configserver.ConfigServerApplication", "eureka-server": "eureka.EurekaServerApplication",
                "core-banking-service": "BankBatchApplication", "payment-service": "payment.PaymentApplication",
                "customer-service": "customer.CustomerApplication", "bff-web-service": "WebBffApplication",
                "bff-mobile-service": "MobileBffApplication", "bff-atm-service": "AtmBffApplication"}
SECRET_NAMES = ["POSTGRES_PASSWORD", "OAUTH2_WEB_SECRET", "OAUTH2_MOBILE_SECRET", "OAUTH2_ATM_SECRET", "OAUTH2_PAYMENT_SECRET"]
ENV = os.environ.copy()
TOKENS = {}


def redact(text):
    for name in SECRET_NAMES:
        if ENV.get(name):
            text = text.replace(ENV[name], "[CREDENCIAL OMITIDA]")
    text = re.sub(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "[JWT OMITIDO]", text)
    return re.sub(r"(?i)\b(Basic|Bearer)\s+[A-Za-z0-9._~+/=-]+", r"\1 [TOKEN OMITIDO]", text)


def report(message):
    line = datetime.now(ZoneInfo("America/Santiago")).isoformat() + " EFT09 " + redact(message)
    print(line, flush=True)
    with EVIDENCE.open("a") as output:
        output.write(line + "\n")


def run(command, data=None, timeout=120, stream=False):
    report("Comando: " + " ".join(command))
    if stream:
        process = subprocess.Popen(command, cwd=ROOT, env=ENV, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        lines = []
        with (ARTIFACTS / "docker-build.log").open("w") as log:
            for line in process.stdout:
                line = redact(line.rstrip())
                log.write(line + "\n")
                lines.append(line)
                if any(marker in line for marker in ["BUILD SUCCESS", " SUCCESS [", "exporting to image", "Built", "ERROR"]):
                    report(line)
        code = process.wait()
        output = "\n".join(lines)
    else:
        process = subprocess.run(command, cwd=ROOT, env=ENV, input=data, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, text=True, timeout=timeout)
        code, output = process.returncode, process.stdout
    if code:
        raise AssertionError("Comando fallo, salida=" + str(code) + "\n" + redact(output[-8000:]))
    return output


def compose(*args, **kwargs):
    return run(COMPOSE + list(args), **kwargs)


def owned(service=None):
    arguments = ["ps", "-a", "-q"]
    if service:
        arguments.append(service)
    return [line for line in compose(*arguments).splitlines() if line]


def resource_snapshot():
    ids = run(["docker", "ps", "-aq"]).splitlines()
    states = {}
    if ids:
        lines = run(["docker", "inspect", "--format", "{{.Id}}|{{.State.Status}}|{{.State.StartedAt}}|{{.State.FinishedAt}}|{{.RestartCount}}", *ids]).splitlines()
        states = {line.split("|", 1)[0]: line.split("|", 1)[1] for line in lines}
    volumes = run(["docker", "volume", "ls", "--format", "{{.Name}}"]).splitlines()
    return {"containers": states, "volumes": volumes}


def prepare():
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    credentials = ARTIFACTS / "credentials.json"
    if not credentials.exists():
        assert not run(["docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=" + PROJECT]).strip(), "El nombre del proyecto de prueba ya esta ocupado"
        with open(credentials, "x", opener=lambda path, flags: os.open(path, flags, 0o600)) as output:
            json.dump({name: secrets.token_urlsafe(32) for name in SECRET_NAMES}, output)
        (ARTIFACTS / "resources-before.json").write_text(json.dumps(resource_snapshot()))
    ENV.update(json.loads(credentials.read_text()))
    ENV["POSTGRES_USER"] = "eft09_user"
    ENV["DEBUG"] = "false"


def audit():
    configuration = json.loads(compose("config", "--format", "json"))
    for name, service in configuration["services"].items():
        assert "container_name" not in service, name
        if name in INTERNAL and not name.startswith("bff-"):
            assert not service.get("ports"), name
        for port in service.get("ports", []):
            assert port.get("host_ip") == "127.0.0.1", name
    for name in ["postgres", "kafka"]:
        assert not configuration["services"][name].get("ports"), name
    for name, volume in configuration["volumes"].items():
        assert volume["name"] == PROJECT + "_" + name and not volume.get("external"), name
    assert configuration["networks"]["default"]["name"] == PROJECT + "_default"
    for name in ["batch-init", "core-banking-service", "customer-service"]:
        assert configuration["services"][name]["environment"]["SPRING_DATASOURCE_URL"] == "jdbc:postgresql://postgres:5432/banco_xyz"
    for name in ["core-banking-service", "payment-service"]:
        assert configuration["services"][name]["environment"]["SPRING_KAFKA_LISTENER_AUTO_STARTUP"] == "false"
    assert configuration["services"]["payment-service"]["environment"]["PAYMENT_KAFKA_WITHDRAWALS_ENABLED"] == "false"
    assert configuration["services"]["core-banking-service"]["environment"]["SPRING_BATCH_JOB_ENABLED"] == "false"
    if not owned():
        for port in PORTS.values():
            with socket.socket() as check:
                check.bind(("127.0.0.1", port))
    for name in ["authorization-server", "config-server", "eureka-server", *INTERNAL]:
        jar = ROOT / name / "target" / (name + "-1.0.0.jar")
        with zipfile.ZipFile(jar) as archive:
            manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
            assert "Start-Class: cl.duoc.bancoxyz." + MAIN_CLASSES[name] + "\r\n" in manifest, name
        report("JAR ejecutable " + name + ": " + str(jar.stat().st_size) + " bytes")
    report("Compose config valido: 12 servicios, red/volumenes propios, backend sin puertos host, seis puertos loopback; sin SimpleDiscovery ni consumidores monetarios")


def request(service, method, path, channel=None, body=None, expected=200):
    headers = {"Accept": "application/json"}
    if channel:
        headers["Authorization"] = "Bearer " + TOKENS[channel]
    if body is not None:
        headers["Content-Type"] = "application/json"
    query = urllib.request.Request("http://127.0.0.1:" + str(PORTS[service]) + path,
                                   data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(query, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    payload = response.read().decode()
    assert response.status == expected, (service, method, path, response.status, redact(payload))
    return json.loads(payload) if payload else None, response.headers


def tokens():
    for channel in ["web", "mobile", "atm", "payment"]:
        secret = ENV["OAUTH2_" + channel.upper() + "_SECRET"]
        scope = "account.operations" if channel == "payment" else channel
        basic = base64.b64encode((channel + "-client:" + secret).encode()).decode()
        query = urllib.request.Request("http://127.0.0.1:19000/oauth2/token", data=("grant_type=client_credentials&scope=" + scope).encode(),
                                       headers={"Authorization": "Basic " + basic, "Content-Type": "application/x-www-form-urlencoded"})
        with urllib.request.urlopen(query, timeout=15) as response:
            payload = json.load(response)
        assert payload["scope"] == scope
        TOKENS[channel] = payload["access_token"]
        report("OAuth2 real: " + channel + "-client, scope=" + scope + ", token emitido (valor omitido)")


def registrations():
    payload, _ = request("eureka-server", "GET", "/eureka/apps")
    apps = payload["applications"].get("application", [])
    if isinstance(apps, dict):
        apps = [apps]
    return {app["name"].lower(): app["instance"] if isinstance(app["instance"], list) else [app["instance"]] for app in apps}


def wait_registrations(replicas=1):
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        registry = registrations()
        if all(len(registry.get(name, [])) == (1 if name.startswith("bff-") else replicas)
               and all(row["status"] == "UP" for row in registry[name]) for name in INTERNAL):
            for name in INTERNAL:
                rows = registry[name]
                assert len({row["instanceId"] for row in rows}) == len(rows), name
                addresses = set()
                for container in owned(name):
                    networks = json.loads(run(["docker", "inspect", "--format", "{{json .NetworkSettings.Networks}}", container]))
                    assert set(networks) == {PROJECT + "_default"}, name
                    addresses.add(networks[PROJECT + "_default"]["IPAddress"])
                assert {row["ipAddr"] for row in rows} == addresses, name
                assert all(row["port"]["$"] == INTERNAL[name] and row["hostName"] != "localhost" for row in rows), name
                report("Eureka " + name + ": " + str(len(rows)) + " UP, ids=" + ",".join(row["instanceId"] for row in rows))
            (ARTIFACTS / "eureka-verified.json").write_text(json.dumps(registry))
            return registry
        time.sleep(2)
    raise AssertionError("Eureka no registro las instancias esperadas: " + str({name: len(rows) for name, rows in registry.items()}))


def curl_inside(url, channel=None):
    options = 'url = "' + url + '"\n'
    if channel:
        options += 'header = "Authorization: Bearer ' + TOKENS[channel] + '"\n'
    output = compose("exec", "-T", "bff-web-service", "curl", "-sS", "--config", "-", "-w", "\n%{http_code}", data=options)
    payload, status = output.rsplit("\n", 1)
    return int(status), json.loads(payload) if payload else None


def access_counts(service, path):
    result = {}
    for container in owned(service):
        text = run(["docker", "exec", container, "cat", "/tmp/eft9-access/central-" + service + ".log"])
        result[container] = sum('"GET ' + path + ' HTTP/' in line and line.endswith(" 200") for line in text.splitlines())
    return result


def database():
    query = """SELECT json_build_object(
      'jobs',(SELECT json_agg(json_build_object('name',i.job_name,'status',e.status)) FROM batch_job_execution e JOIN batch_job_instance i USING(job_instance_id)),
      'history',json_build_object('daily',(SELECT count(*) FROM transacciones_procesadas),'monthly',(SELECT count(*) FROM intereses_mensuales),'annual',(SELECT count(*) FROM movimientos_anuales)),
      'balances',(SELECT json_agg(json_build_object('id',cuenta_id,'balance',saldo_operativo) ORDER BY cuenta_id) FROM cuentas),
      'operations',(SELECT count(*) FROM operaciones_cuenta),'withdrawals',(SELECT count(*) FROM withdrawal_transactions));"""
    return json.loads(compose("exec", "-T", "postgres", "psql", "-U", ENV["POSTGRES_USER"], "-d", "banco_xyz", "-At", "-c", query))


def health_and_config():
    services = ["authorization-server", "config-server", "eureka-server", *INTERNAL]
    for name in services:
        for container in owned(name):
            health = run(["docker", "inspect", "--format", "{{.State.Health.Status}}", container]).strip()
            assert health == "healthy", (name, health)
    for name in INTERNAL:
        payload, _ = request("config-server", "GET", "/" + name + "/validacion")
        properties = {}
        for source in reversed(payload["propertySources"]):
            properties.update(source["source"])
        assert properties["server.tomcat.accesslog.prefix"] == "central-${spring.application.name}", name
        assert properties["eureka.instance.prefer-ip-address"] in [True, "${EUREKA_PREFER_IP_ADDRESS:true}"], name
        for container in owned(name):
            response = json.loads(run(["docker", "exec", container, "curl", "-fsS", "http://localhost:" + str(INTERNAL[name]) + "/actuator/health"]))
            assert response["status"] == "UP" and "components" in response, name
            log = run(["docker", "exec", container, "cat", "/tmp/eft9-access/central-" + name + ".log"])
            assert "/actuator/health" in log, name
        if name.startswith("bff-"):
            channel = name.split("-")[1].capitalize()
            assert properties["resilience4j.circuitbreaker.instances.core" + channel + ".sliding-window-size"] == 5
            if channel != "Atm":
                assert properties["resilience4j.circuitbreaker.instances.customer" + channel + ".sliding-window-size"] == 5
                assert properties["resilience4j.circuitbreaker.instances.payment" + channel + ".sliding-window-size"] == 5
        report("Config Server real " + name + ": perfil validacion, health detallado y archivo central de acceso aplicados; fuentes=" + ",".join(source["name"] for source in payload["propertySources"]))
    status, origin = curl_inside("http://core-banking-service:8081/api/config/origin", "web")
    assert status == 200 and "Spring Cloud Config Server" in origin["configOrigin"]
    report("Core /api/config/origin confirma configuracion central; infraestructura y nueve aplicaciones healthy")


def functional():
    tokens()
    wait_registrations()
    health_and_config()
    customer, headers = request("bff-web-service", "POST", "/api/bff/web/customers", "web",
                                {"firstName": "Cliente", "lastName": "Validacion", "email": str(uuid.uuid4()) + "@example.test", "profile": "ESTANDAR"}, 201)
    customer_id = customer["customerId"]
    assert headers["Location"] == "/api/bff/web/customers/" + str(customer_id)
    request("bff-web-service", "GET", "/api/bff/web/customers/" + str(customer_id), "web")
    accounts = []
    for number in range(3):
        account, headers = request("bff-web-service", "POST", "/api/bff/web/accounts", "web",
                                   {"customerId": customer_id, "accountName": "Cuenta sintetica " + str(number), "accountType": "ahorro", "interestRate": 0.01}, 201)
        assert account["initialBalance"] == 0 and account["finalBalance"] == 0
        assert headers["Location"] == "/api/bff/web/accounts/" + str(account["accountId"])
        accounts.append(account["accountId"])
    source, target, empty = accounts
    request("bff-web-service", "PUT", "/api/bff/web/accounts/" + str(source), "web",
            {"accountName": "Cuenta actualizada", "accountType": "ahorro", "interestRate": 0.02})
    request("bff-web-service", "POST", "/api/bff/web/accounts/" + str(empty) + "/close", "web")
    operations = {}
    for action, body in [("deposits", {"accountId": source, "amount": 100000}),
                         ("", {"accountId": source, "amount": 10000}),
                         ("transfers", {"sourceAccountId": source, "targetAccountId": target, "amount": 20000})]:
        operation_id = str(uuid.uuid4())
        body["operationId"] = operation_id
        result, _ = request("bff-web-service", "POST", "/api/bff/web/payments" + ("/" + action if action else ""), "web", body)
        assert result["status"] == "COMPLETED" and result["operationId"] == operation_id
        operations[action or "payment"] = operation_id
        request("bff-web-service", "GET", "/api/bff/web/payments/operations/" + operation_id, "web")
    full, _ = request("bff-web-service", "GET", "/api/bff/web/accounts/" + str(source), "web")
    light, _ = request("bff-mobile-service", "GET", "/api/bff/mobile/accounts/" + str(source), "mobile")
    client, _ = request("bff-mobile-service", "GET", "/api/bff/mobile/customers/" + str(customer_id), "mobile")
    operation, _ = request("bff-mobile-service", "GET", "/api/bff/mobile/payments/operations/" + operations["deposits"], "mobile")
    assert full["finalBalance"] == light["finalBalance"] == 70000
    assert len(full) == 7 and len(light) == 4 and len(client) == 3 and len(operation) == 4
    request("bff-web-service", "GET", "/api/bff/web/accounts/" + str(source) + "/annual-statement", "web")
    request("bff-mobile-service", "GET", "/api/bff/mobile/accounts/" + str(source) + "/transactions", "mobile")
    for name, path, wrong in [("bff-web-service", "/api/bff/web/accounts/" + str(source), "mobile"),
                               ("bff-mobile-service", "/api/bff/mobile/customers/" + str(customer_id), "web"),
                               ("bff-atm-service", "/api/bff/atm/accounts/" + str(source) + "/balance", "web")]:
        request(name, "GET", path, expected=401)
        request(name, "GET", path, wrong, expected=403)
    internal = "/api/core/internal/operations/" + operations["deposits"]
    assert curl_inside("http://core-banking-service:8081" + internal, "payment")[0] == 200
    assert curl_inside("http://core-banking-service:8081" + internal, "web")[0] == 403
    request("bff-web-service", "GET", "/api/core/internal/operations/" + operations["deposits"], "web", expected=404)
    balance, _ = request("bff-atm-service", "GET", "/api/bff/atm/accounts/" + str(source) + "/balance", "atm")
    assert balance["balance"] == 70000
    pending, _ = request("bff-atm-service", "POST", "/api/bff/atm/accounts/" + str(source) + "/withdraw", "atm", {"amount": 20000}, 202)
    assert pending["status"] == "PENDING"
    event_text = compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server", "kafka:9092",
                         "--topic", "bank.withdrawal.requested", "--from-beginning", "--max-messages", "1", "--timeout-ms", "10000")
    event = next(json.loads(line) for line in event_text.splitlines() if line.startswith("{"))
    assert event["transactionId"] == pending["transactionId"] and event["accountId"] == source and event["amount"] == 20000
    groups = compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "kafka:9092", "--list")
    assert "core-banking-withdrawal-group" not in groups
    snapshot = database()
    assert snapshot["withdrawals"] == 0 and snapshot["operations"] == 3
    assert len(snapshot["jobs"]) == 3 and all(row["status"] == "COMPLETED" for row in snapshot["jobs"])
    assert {row["name"] for row in snapshot["jobs"]} == {"dailyTransactionsJob", "monthlyInterestJob", "annualStatementsJob"}
    assert all(count > 0 for count in snapshot["history"].values())
    state = {"customer": customer_id, "source": source, "target": target, "deposit": operations["deposits"], "before": snapshot}
    (ARTIFACTS / "functional-state.json").write_text(json.dumps(state))
    report("HTTP real via Eureka/LoadBalancer: Web registro/consulta cliente, apertura cero/mantenimiento/cierre/cuenta, deposito/pago/transferencia/consulta; saldos 70000/20000. Mobile campos cuenta4/cliente3/operacion4 frente a cuentaWeb7. Historiales conservados")
    report("OAuth2: cuatro clientes, scopes correctos, Bearer propagado; 401 sin token y 403 cruzado; Pagos -> Core interno 200 con account.operations, Web interno 403; contabilidad no expuesta en BFF")
    report("ATM 202/PENDING y evento verificado en Kafka propio; observador de prueba sin cargos. Sin grupo monetario activo, cero withdrawal_transactions, saldo permanece 70000")
    report("Batch inicial: tres COMPLETED; historicos=" + str(snapshot["history"]) + "; cuentas nuevas y saldo operativo creados exclusivamente por APIs")


def scaling():
    tokens()
    compose("up", "-d", "--no-deps", "--wait", "--wait-timeout", "240", "--scale", "core-banking-service=2",
            "--scale", "payment-service=2", "--scale", "customer-service=2", "core-banking-service", "payment-service", "customer-service", timeout=300)
    wait_registrations(2)
    health_and_config()
    time.sleep(10)
    state = json.loads((ARTIFACTS / "functional-state.json").read_text())
    for service, public, internal in [
            ("core-banking-service", "/api/bff/web/accounts/" + str(state["source"]), "/api/core/accounts/" + str(state["source"])),
            ("customer-service", "/api/bff/web/customers/" + str(state["customer"]), "/api/customers/" + str(state["customer"])),
            ("payment-service", "/api/bff/web/payments/operations/" + state["deposit"], "/api/payments/operations/" + state["deposit"])]:
        before = access_counts(service, internal)
        for _ in range(48):
            request("bff-web-service", "GET", public, "web")
        # Tomcat vacia el buffer de acceso en segundo plano; espera la evidencia real.
        deadline = time.monotonic() + 35
        while True:
            after = access_counts(service, internal)
            delta = {container: after[container] - before[container] for container in after}
            if sum(delta.values()) == 48 or time.monotonic() >= deadline:
                break
            time.sleep(2)
        assert len(delta) == 2 and all(count > 0 for count in delta.values()) and sum(delta.values()) == 48, (service, delta)
        report("Distribucion real " + service + ": 48 GET por BFF Web, HTTP200, accesos por instancia=" + str(delta))
    for _ in range(20):
        response, _ = request("bff-web-service", "POST", "/api/bff/web/payments/deposits", "web",
                              {"operationId": state["deposit"], "accountId": state["source"], "amount": 100000})
        assert response["operationId"] == state["deposit"] and response["status"] == "COMPLETED"
    snapshot = database()
    assert snapshot["balances"] == state["before"]["balances"] and snapshot["operations"] == 3
    report("Idempotencia con dos replicas Pagos/Core: 20 reenvios del UUID original, tres operaciones totales y saldos sin cambio")


def batch_again():
    state = json.loads((ARTIFACTS / "functional-state.json").read_text())
    compose("start", "batch-init")
    container = owned("batch-init")[0]
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        result = run(["docker", "inspect", "--format", "{{.State.Status}}|{{.State.ExitCode}}", container]).strip()
        if result.startswith("exited"):
            assert result == "exited|0", result
            break
        time.sleep(5)
    else:
        raise AssertionError("batch-init no termino")
    snapshot = database()
    assert len(snapshot["jobs"]) == 6 and all(row["status"] == "COMPLETED" for row in snapshot["jobs"])
    assert snapshot["history"] == state["before"]["history"]
    assert snapshot["balances"] == state["before"]["balances"] and snapshot["operations"] == 3 and snapshot["withdrawals"] == 0
    topics = compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "kafka:9092", "--list")
    assert {"bank.withdrawal.requested", "bank.withdrawal.completed", "bank.withdrawal.rejected"}.issubset(set(topics.splitlines()))
    groups = compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "kafka:9092", "--list")
    assert "core-banking-withdrawal-group" not in groups
    report("Kafka aislado: tres topics existentes conservados, grupo monetario ausente y Payment auto.offset.reset=none sin cambios; flujo automatico reservado al Paso 12")
    report("Reejecucion Batch aislada: seis ejecuciones COMPLETED (tres iniciales + tres repetidas), conteos historicos iguales, operaciones3 y saldos70000/20000 intactos; replicas Core no ejecutan Jobs")


def restart():
    compose("restart", "core-banking-service", "payment-service", "customer-service", timeout=120)
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if all(run(["docker", "inspect", "--format", "{{.State.Health.Status}}", container]).strip() == "healthy"
               for name in ["core-banking-service", "payment-service", "customer-service"] for container in owned(name)):
            break
        time.sleep(3)
    else:
        raise AssertionError("Replicas no recuperaron salud tras reinicio")
    tokens()
    wait_registrations(2)
    state = json.loads((ARTIFACTS / "functional-state.json").read_text())
    snapshot = database()
    assert snapshot["balances"] == state["before"]["balances"] and snapshot["operations"] == 3
    assert len(snapshot["jobs"]) == 6 and snapshot["withdrawals"] == 0
    result, _ = request("bff-mobile-service", "GET", "/api/bff/mobile/accounts/" + str(state["source"]), "mobile")
    assert result["finalBalance"] == 70000
    request("bff-web-service", "GET", "/api/bff/web/payments/operations/" + state["deposit"], "web")
    report("Reinicio real de seis replicas: healthy/UP, saldo70000, operaciones3, Jobs6 sin ejecuciones automaticas adicionales; consultas BFF recuperadas")


def resources():
    before = json.loads((ARTIFACTS / "resources-before.json").read_text())
    after = resource_snapshot()
    assert all(after["containers"].get(key) == value for key, value in before["containers"].items())
    assert set(before["volumes"]).issubset(after["volumes"])
    for service, volume in [("postgres", "postgres_data"), ("kafka", "kafka_data")]:
        mounts = json.loads(run(["docker", "inspect", "--format", "{{json .Mounts}}", owned(service)[0]]))
        names = {mount["Name"] for mount in mounts if mount["Type"] == "volume"}
        assert PROJECT + "_" + volume in names and names.isdisjoint(before["volumes"])
        report("Volumenes auxiliares de imagen " + service + ": " + str(len(names) - 1) + " nuevos, sin reutilizar recursos anteriores")
        report("Persistencia real " + service + ": " + PROJECT + "_" + volume + ", independiente de volumenes anteriores")
    for container in owned():
        logs = run(["docker", "logs", container])
        assert not any(ENV[name] in logs for name in SECRET_NAMES), "Credencial detectada en logs propios (valor omitido)"
        assert not re.search(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", logs), "JWT detectado en logs propios (valor omitido)"
        assert "Using generated security password:" not in logs, "Credencial automatica detectada en logs propios (valor omitido)"
    report("Logs de contenedores propios comprobados: sin credenciales de prueba ni JWT completos")
    report("Recursos anteriores conservados: " + str(len(before["containers"])) + " contenedores sin cambios de estado/reinicios; " + str(len(before["volumes"])) + " volumenes conservados. Recursos propios=" + str(len(owned())) + "; volumenes de prueba preservados")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=["audit", "build", "up", "functional", "scale", "batch", "restart", "resources", "stop"])
    args = parser.parse_args()
    prepare()
    report("Etapa de verificacion: " + args.stage + "; proyecto=" + PROJECT)
    if args.stage == "audit":
        audit()
    elif args.stage == "build":
        compose("build", timeout=1800, stream=True)
    elif args.stage == "up":
        audit()
        compose("up", "-d", "--wait", "--wait-timeout", "420", timeout=480)
        report("Compose aislado iniciado; healthchecks satisfechos y batch-init finalizado")
    elif args.stage == "functional":
        functional()
    elif args.stage == "scale":
        scaling()
    elif args.stage == "batch":
        batch_again()
    elif args.stage == "restart":
        restart()
    elif args.stage == "resources":
        resources()
    else:
        compose("stop", timeout=120)
        resources()
    report("Resultado de etapa " + args.stage + ": SATISFACTORIO")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        report("FALLO: " + redact(str(error)))
        raise SystemExit(1)
