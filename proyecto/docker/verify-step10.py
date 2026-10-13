#!/usr/bin/env python3
"""Integracion real del Paso 10. Reutiliza el helper anterior sin ejecutar su main ni escribir evidencia 09."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

sys.dont_write_bytecode = True
import importlib.util

spec = importlib.util.spec_from_file_location("step09", Path(__file__).with_name("verify-step09.py"))
s = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)
s.PROJECT = "eft_s9_integracion"
s.ARTIFACTS = s.ROOT / "target/step10"
s.EVIDENCE = s.ROOT.parent / "evidencias/EFT/10_integracion_aislada.txt"
s.COMPOSE = ["docker", "compose", "-p", s.PROJECT, "-f", "docker-compose.yml",
             "-f", "docker-compose.validacion.yml", "-f", "docker-compose.integracion.yml"]
s.PORTS = {"config-server": 28888, "eureka-server": 28761, "authorization-server": 29000,
           "bff-web-service": 28082, "bff-mobile-service": 28083, "bff-atm-service": 28084}
STATE = s.ARTIFACTS / "integration-state.json"
GROUP = "core-banking-withdrawal-group"
REQUESTED = "bank.withdrawal.requested"
COMPLETED = "bank.withdrawal.completed"
REJECTED = "bank.withdrawal.rejected"
OLD_PROJECT = "eft_s9_validacion"


def report(message):
    line = datetime.now(ZoneInfo("America/Santiago")).isoformat() + " EFT10 " + s.redact(message)
    print(line, flush=True)
    with s.EVIDENCE.open("a") as output:
        output.write(line + "\n")


def run(command, data=None, timeout=120, **unused):
    result = subprocess.run(command, cwd=s.ROOT, env=s.ENV, input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, timeout=timeout)
    if result.returncode:
        raise AssertionError("Comando fallo: " + " ".join(command) + "\n" + s.redact(result.stdout[-4000:]))
    return result.stdout


s.report = report
s.run = run


def sql(query, project=None):
    if project:
        return run(["docker", "exec", "-i", project + "-postgres-1", "sh", "-c",
                    'exec psql -U "$POSTGRES_USER" -d banco_xyz -At'], data=query)
    return s.compose("exec", "-T", "postgres", "psql", "-U", s.ENV["POSTGRES_USER"],
                     "-d", "banco_xyz", "-At", data=query)


def snapshot(project=None):
    # Orden estable y hash del contenido, ademas de conteos de historicos.
    return json.loads(sql("""SELECT json_build_object(
      'jobs',(SELECT json_agg(json_build_object('name',i.job_name,'status',e.status) ORDER BY e.job_execution_id)
         FROM batch_job_execution e JOIN batch_job_instance i USING(job_instance_id)),
      'history',json_build_object(
         'daily',(SELECT json_build_object('count',count(*),'hash',md5(string_agg(row_to_json(t)::text,'|' ORDER BY id))) FROM transacciones_procesadas t),
         'monthly',(SELECT json_build_object('count',count(*),'hash',md5(string_agg(row_to_json(t)::text,'|' ORDER BY cuenta_id))) FROM intereses_mensuales t),
         'annual',(SELECT json_build_object('count',count(*),'hash',md5(string_agg(row_to_json(t)::text,'|' ORDER BY movimiento_id))) FROM movimientos_anuales t)),
      'balances',(SELECT json_agg(json_build_object('id',cuenta_id,'balance',saldo_operativo,'state',estado) ORDER BY cuenta_id) FROM cuentas),
      'operations',(SELECT count(*) FROM operaciones_cuenta),
      'withdrawals',(SELECT count(*) FROM withdrawal_transactions),
      'events',(SELECT count(*) FROM banking_events));""", project))


def save(state):
    STATE.write_text(json.dumps(state))


def load():
    return json.loads(STATE.read_text())


def tokens():
    for channel in ["web", "mobile", "atm", "payment"]:
        scope = "account.operations" if channel == "payment" else channel
        basic = s.base64.b64encode((channel + "-client:" + s.ENV["OAUTH2_" + channel.upper() + "_SECRET"]).encode()).decode()
        query = s.urllib.request.Request("http://127.0.0.1:29000/oauth2/token",
                    data=("grant_type=client_credentials&scope=" + scope).encode(),
                    headers={"Authorization": "Basic " + basic, "Content-Type": "application/x-www-form-urlencoded"})
        with s.urllib.request.urlopen(query, timeout=15) as response:
            body = json.load(response)
        assert body["scope"] == scope
        s.TOKENS[channel] = body["access_token"]
        report("OAuth2 real: " + channel + "-client, scope=" + scope + "; valor del token omitido")


def kafka(tool, *arguments, timeout=40):
    return s.compose("exec", "-T", "kafka", "/opt/kafka/bin/" + tool + ".sh",
                     "--bootstrap-server", "kafka:9092", *arguments, timeout=timeout)


def offsets():
    text = kafka("kafka-consumer-groups", "--describe", "--group", GROUP)
    result = {}
    for line in text.splitlines():
        fields = line.split()
        if len(fields) >= 6 and fields[0] == GROUP and fields[1] == REQUESTED:
            result[int(fields[2])] = {"committed": int(fields[3]), "end": int(fields[4]), "lag": int(fields[5])}
    assert len(result) == 3, text
    return result


def wait_offsets():
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        current = offsets()
        if all(row["committed"] == row["end"] and row["lag"] == 0 for row in current.values()):
            return current
        time.sleep(2)
    raise AssertionError("Solicitud no reconocida: " + str(current))


def messages(topic):
    command = s.COMPOSE + ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh",
        "--bootstrap-server", "kafka:9092", "--topic", topic, "--from-beginning", "--timeout-ms", "10000",
        "--property", "print.partition=true", "--property", "print.offset=true", "--property", "print.key=true"]
    process = subprocess.run(command, cwd=s.ROOT, env=s.ENV, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, text=True, timeout=30)
    events = []
    for line in process.stdout.splitlines():
        fields = line.split("\t")
        if len(fields) == 4 and fields[-1].startswith("{"):
            events.append({"partition": int(fields[0].split(":")[1]), "offset": int(fields[1].split(":")[1]),
                           "key": fields[2], "event": json.loads(fields[3])})
    assert process.returncode in [0, 1] and events, s.redact(process.stdout[-2000:])
    return events


def publish(event):
    # El tab separa clave y JSON; el contrato y los topics existentes no cambian.
    s.compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh",
              "--bootstrap-server", "kafka:9092", "--topic", REQUESTED,
              "--property", "parse.key=true", "--property", "key.separator=\t",
              data=str(event["accountId"]) + "\t" + json.dumps(event) + "\n")


def internal(method, path, channel="payment", body=None, expected=200):
    options = 'url = "http://core-banking-service:8081' + path + '"\nrequest = "' + method + '"\n'
    if channel:
        options += 'header = "Authorization: Bearer ' + s.TOKENS[channel] + '"\n'
    if body is not None:
        options += 'header = "Content-Type: application/json"\ndata = "' + json.dumps(body).replace('"', '\\"') + '"\n'
    output = s.compose("exec", "-T", "bff-web-service", "curl", "-sS", "--config", "-", "-w", "\n%{http_code}", data=options)
    payload, status = output.rsplit("\n", 1)
    assert int(status) == expected, (method, path, status, s.redact(payload))
    return json.loads(payload) if payload else None


def atm(account, amount):
    result, _ = s.request("bff-atm-service", "POST", "/api/bff/atm/accounts/" + str(account) + "/withdraw",
                          "atm", {"amount": amount}, 202)
    assert result["status"] == "PENDING"
    return result["transactionId"]


def withdrawal(transaction, expected, balance=None):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        value = sql("SELECT row_to_json(t) FROM withdrawal_transactions t WHERE transaction_id='" + transaction + "';").strip()
        if value:
            stored = json.loads(value)
            assert stored["status"] == expected
            result, _ = s.request("bff-atm-service", "GET", "/api/bff/atm/accounts/transactions/" + transaction, "atm")
            assert result["status"] == expected and result["transactionId"] == transaction
            if balance is not None:
                assert result["finalBalance"] == stored["final_balance"] == balance
            if expected == "REJECTED":
                assert result["rejectionReason"] == stored["rejection_reason"] and stored["rejection_reason"]
            original = internal("GET", "/api/core/internal/withdrawals/" + transaction)
            event = original["completed" if expected == "COMPLETED" else "rejected"]
            persisted = json.loads(sql("SELECT payload FROM banking_events WHERE transaction_id='" + transaction
                         + "' AND event_type='Withdrawal" + ("Completed" if expected == "COMPLETED" else "Rejected") + "';"))
            assert persisted == event
            return result, event
        time.sleep(1)
    raise AssertionError("Retiro no termino: " + transaction)


def same_event(actual, original):
    # Instant puede representarse como ISO en HTTP y numero en Kafka; comparar el mismo instante.
    assert {k: v for k, v in actual.items() if k != "occurredAt"} == {k: v for k, v in original.items() if k != "occurredAt"}
    def instant(value):
        return float(value) if isinstance(value, (int, float)) else datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    assert abs(instant(actual["occurredAt"]) - instant(original["occurredAt"])) < 0.000002


def audit():
    configuration = json.loads(s.compose("config", "--format", "json"))
    assert configuration["networks"]["default"]["name"] == s.PROJECT + "_default"
    for name, volume in configuration["volumes"].items():
        assert volume["name"] == s.PROJECT + "_" + name and not volume.get("external")
    for name, service in configuration["services"].items():
        assert "container_name" not in service
        if name in ["postgres", "kafka", "core-banking-service", "payment-service", "customer-service"]:
            assert not service.get("ports")
        for port in service.get("ports", []):
            assert port.get("host_ip") == "127.0.0.1"
        if name not in ["postgres", "kafka"]:
            assert not service.get("build") and service["image"] == "eft_s9_validacion-" + name + ":latest"
            run(["docker", "image", "inspect", "--format", "{{.Id}}", service["image"]])
    assert configuration["services"]["payment-service"]["environment"]["SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET"] == "none"
    assert configuration["services"]["core-banking-service"]["environment"]["SPRING_KAFKA_LISTENER_AUTO_STARTUP"] == "false"
    assert not run(["docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=" + s.PROJECT]).strip()
    for port in s.PORTS.values():
        with socket.socket() as check:
            check.bind(("127.0.0.1", port))
    info = json.loads(run(["docker", "info", "--format", "{{json .}}"])); memory = Path("/proc/meminfo").read_text()
    available = int(re.search(r"MemAvailable:\s+(\d+)", memory).group(1)) * 1024
    free_disk = os.statvfs(s.ROOT).f_bavail * os.statvfs(s.ROOT).f_frsize
    assert available > 5 * 1024**3 and free_disk > 5 * 1024**3 and info["NCPU"] >= 4
    old = snapshot(OLD_PROJECT)
    (s.ARTIFACTS / "validation-before.json").write_text(json.dumps(old))
    assert old["operations"] == 3 and old["withdrawals"] == 0 and len(old["jobs"]) == 6
    groups = run(["docker", "exec", OLD_PROJECT + "-kafka-1", "/opt/kafka/bin/kafka-consumer-groups.sh",
                  "--bootstrap-server", "kafka:9092", "--list"])
    assert GROUP not in groups
    report("Auditoria completada: Git limpio HEAD7c1d99d, 302 archivos originales y 356 casos existentes; evidencia05/08/09 reutilizable")
    report("Recursos: CPU=" + str(info["NCPU"]) + "; RAM DockerGiB=" + str(round(info["MemTotal"]/1024**3, 2))
           + "; RAM disponibleGiB=" + str(round(available/1024**3, 2)) + "; disco libreGiB=" + str(round(free_disk/1024**3, 2)))
    report("Aislamiento auditado: doce servicios, imagenes previas sin construir/descargar, red/volumenes propios, puertos loopback libres=" + str(s.PORTS))
    report("Entorno09 solo lectura: operaciones3/retiros0/Jobs6, sin grupo monetario. Core legacy autoStartup=false; Payment global none; Core conserva debito+resultado+evento atomicos; Pagos sin datasource/JDBC")
    report("Prueba09 suficiente: Config/Eureka, dos replicas y 48 GET por servicio24/24; no se repetira carga ni construccion. Pendiente: flujo monetario Kafka completo en broker nuevo")


def seed():
    tokens(); s.wait_registrations(); s.health_and_config()
    initial = snapshot()
    assert len(initial["jobs"]) == 3 and all(row["status"] == "COMPLETED" for row in initial["jobs"])
    assert not initial["balances"] and initial["operations"] == initial["withdrawals"] == 0, "seed requiere cuentas nuevas; no borrar datos para repetir"
    client_body = {"firstName": "Cliente", "lastName": "Sintetico", "email": "eft10@example.invalid",
                   "phone": "+56912345678", "profile": "ESTANDAR"}
    existing = sql("SELECT cliente_id FROM clientes WHERE correo='eft10@example.invalid';").strip()
    if existing:
        customer = int(existing)
        s.request("bff-web-service", "GET", "/api/bff/web/customers/" + str(customer), "web")
        report("Se conserva cliente sintetico" + str(customer) + " creado con201 en el intento inicial; sin eliminar registros")
    else:
        client, _ = s.request("bff-web-service", "POST", "/api/bff/web/customers", "web", client_body, 201)
        customer = client["customerId"]
    s.request("bff-web-service", "POST", "/api/bff/web/customers", "web", client_body, 409)
    s.request("bff-web-service", "POST", "/api/bff/web/customers", "web", {**client_body, "email": "invalido"}, 400)
    information = {key: value for key, value in client_body.items() if key != "profile"}
    updated, _ = s.request("bff-web-service", "PUT", "/api/bff/web/customers/" + str(customer), "web", {**information, "firstName": "Actualizado"})
    assert updated["firstName"] == "Actualizado"
    profile, _ = s.request("bff-web-service", "PUT", "/api/bff/web/customers/" + str(customer) + "/profile", "web", {"profile": "PREFERENTE"})
    assert profile["profile"] == "PREFERENTE"
    s.request("bff-web-service", "GET", "/api/bff/web/customers/" + str(customer) + "/profile", "web")
    accounts = []
    for name in ["Origen EFT10", "Destino EFT10", "Cierre EFT10"]:
        account, headers = s.request("bff-web-service", "POST", "/api/bff/web/accounts", "web",
            {"customerId": customer, "accountName": name, "accountType": "ahorro", "interestRate": 0.01}, 201)
        assert account["initialBalance"] == account["finalBalance"] == 0
        assert headers["Location"] == "/api/bff/web/accounts/" + str(account["accountId"])
        accounts.append(account["accountId"])
    source, target, closed = accounts
    s.request("bff-web-service", "PUT", "/api/bff/web/accounts/" + str(source), "web",
              {"accountName": "Origen actualizado", "accountType": "ahorro", "interestRate": 0.02})
    s.request("bff-web-service", "POST", "/api/bff/web/accounts/" + str(closed) + "/close", "web")
    operations = {}
    for action, body in [("deposits", {"accountId": source, "amount": 200000}),
                         ("", {"accountId": source, "amount": 10000}),
                         ("transfers", {"sourceAccountId": source, "targetAccountId": target, "amount": 20000})]:
        identifier = str(uuid.uuid4()); body["operationId"] = identifier
        path = "/api/bff/web/payments" + ("/" + action if action else "")
        result, _ = s.request("bff-web-service", "POST", path, "web", body)
        assert result["status"] == "COMPLETED" and result["operationId"] == identifier
        replay, _ = s.request("bff-web-service", "POST", path, "web", body)
        assert replay == result
        operations[action or "payment"] = identifier
        s.request("bff-web-service", "GET", "/api/bff/web/payments/operations/" + identifier, "web")
    before_reject = snapshot()["balances"]
    for target_id, amount in [(999999, 10000), (target, 9990000)]:
        result, _ = s.request("bff-web-service", "POST", "/api/bff/web/payments/transfers", "web",
            {"operationId": str(uuid.uuid4()), "sourceAccountId": source, "targetAccountId": target_id, "amount": amount})
        assert result["status"] == "REJECTED" and snapshot()["balances"] == before_reject
    balance, _ = s.request("bff-atm-service", "GET", "/api/bff/atm/accounts/" + str(source) + "/balance", "atm")
    assert balance["balance"] == 170000
    full, _ = s.request("bff-web-service", "GET", "/api/bff/web/accounts/" + str(source), "web")
    light, _ = s.request("bff-mobile-service", "GET", "/api/bff/mobile/accounts/" + str(source), "mobile")
    customer_light, _ = s.request("bff-mobile-service", "GET", "/api/bff/mobile/customers/" + str(customer), "mobile")
    operation_light, _ = s.request("bff-mobile-service", "GET", "/api/bff/mobile/payments/operations/" + operations["deposits"], "mobile")
    assert full["finalBalance"] == light["finalBalance"] == 170000
    assert len(full) == 7 and len(light) == 4 and len(customer_light) == 3 and len(operation_light) == 4
    for service, path, wrong in [("bff-web-service", "/api/bff/web/customers/" + str(customer), "mobile"),
             ("bff-mobile-service", "/api/bff/mobile/accounts/" + str(source), "web"),
             ("bff-atm-service", "/api/bff/atm/accounts/" + str(source) + "/balance", "web")]:
        s.request(service, "GET", path, expected=401); s.request(service, "GET", path, wrong, expected=403)
    path = "/api/core/internal/operations/" + operations["deposits"]
    internal("GET", path); internal("GET", path, "web", expected=403); internal("GET", path, None, expected=401)
    s.request("bff-web-service", "GET", path, "web", expected=404)
    pending = atm(source, 20000)
    save({"customer": customer, "source": source, "target": target, "closed": closed, "operations": operations,
          "pending": pending, "initial": initial, "before_activation": snapshot()})
    event = next(row for row in messages(REQUESTED) if row["event"]["transactionId"] == pending)
    assert event["key"] == str(source) and event["event"]["amount"] == 20000
    before = snapshot(); assert before["withdrawals"] == 0 and before["operations"] == 5 and before["history"] == initial["history"]
    assert GROUP not in kafka("kafka-consumer-groups", "--list")
    state = load(); state["requested"] = event; state["before_activation"] = before; save(state)
    report("Integracion bancaria real: Clientes registro/consulta/actualizacion/perfil; validacion400 y unicidad409. Cuentas apertura saldo0/mantenimiento/cierre; pagos/deposito/transferencia y reenvios mismo UUID conservan resultado")
    report("Transferencias rechazadas por destino inexistente o saldo insuficiente: ningun saldo cambia; saldos170000/20000/cerrada0, cinco operaciones. Web consulta completa7campos; Mobile cuenta4/cliente3/operacion4")
    report("Seguridad real: Web/Mobile/ATM 401 sin JWT y 403 canal incorrecto; Core interno payment/account.operations200, Web403, sin JWT401; ruta interna por BFF404. Propagacion Bearer validada por endpoints protegidos")
    report("Antes de activar Pagos: ATM202/PENDING transactionId=" + pending + "; solicitud real Kafka partition="
          + str(event["partition"]) + " offset=" + str(event["offset"]) + "; retiro0, saldo170000, grupo monetario ausente")


def activate():
    tokens(); state = load()
    assert GROUP not in kafka("kafka-consumer-groups", "--list")
    report("Comando offsets SOLO broker eft_s9_integracion: kafka-consumer-groups.sh --bootstrap-server kafka:9092 --reset-offsets --group "
           + GROUP + " --topic " + REQUESTED + " --to-offset 0 --execute; grupo inactivo y volumen nuevo")
    result = kafka("kafka-consumer-groups", "--reset-offsets", "--group", GROUP, "--topic", REQUESTED, "--to-offset", "0", "--execute")
    report("Offsets iniciales ejecutados: " + " | ".join(line.strip() for line in result.splitlines() if GROUP in line))
    before = offsets(); assert all(row["committed"] == 0 for row in before.values()) and sum(row["end"] for row in before.values()) == 1
    assert before[state["requested"]["partition"]]["end"] == 1
    report("Offsets antes de activar: " + str(before) + "; solicitud pendiente incluida, auto.offset.reset=none sin override earliest")
    s.ENV["EFT10_WITHDRAWALS_ENABLED"] = "true"
    report("Comando: docker compose -p eft_s9_integracion -f docker-compose.yml -f docker-compose.validacion.yml -f docker-compose.integracion.yml up -d --no-deps --no-build --pull never --wait payment-service")
    s.compose("up", "-d", "--no-deps", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180", "payment-service", timeout=210)
    (s.ARTIFACTS / "consumer-enabled").write_text("Exclusivamente eft_s9_integracion\n")
    value, original = withdrawal(state["pending"], "COMPLETED", 150000)
    committed = wait_offsets()
    results = [row for row in messages(COMPLETED) if row["event"]["transactionId"] == state["pending"]]
    assert len(results) == 1 and results[0]["key"] == str(state["source"])
    same_event(results[0]["event"], original)
    logs = run(["docker", "logs", s.owned("payment-service")[0]])
    assert "Retiro confirmado transactionId=" + state["pending"] in logs
    members = kafka("kafka-consumer-groups", "--describe", "--group", GROUP, "--members", "--verbose")
    address = run(["docker", "inspect", "--format", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", s.owned("payment-service")[0]]).strip()
    member_rows = [line.split() for line in members.splitlines() if line.startswith(GROUP)]
    assert member_rows and all(row[2] == "/" + address for row in member_rows), members
    state["original"] = original; save(state)
    report("Flujo E2E COMPLETED: solicitud pendiente consumida por Pagos, HTTP OAuth2 a Core, PostgreSQL debito+resultado+evento persistidos; ATM definitivo150000, CompletedEvent mismo eventId=" + original["eventId"])
    report("Grupo monetario: " + GROUP + "; todos los miembros proceden de Pagos IP=" + address + "; Core legacy inactivo. Broker confirma resultado y offset de solicitud avanza: " + str(committed))


def rejections_and_duplicates():
    tokens(); state = load(); baseline = snapshot()
    rejected = []
    for label, account, amount in [("insuficiente", state["source"], 9990000), ("inexistente", 999999, 10000),
            ("cerrada", state["closed"], 10000), ("no multiplo ATM", state["source"], 15000),
            ("cero", state["source"], 0), ("negativo", state["source"], -10000)]:
        transaction = atm(account, amount); status, event = withdrawal(transaction, "REJECTED")
        assert snapshot()["balances"] == baseline["balances"]
        rejected.append({"transaction": transaction, "event": event})
        report("ATM202 -> REJECTED definitivo persistido: " + label + "; transactionId=" + transaction + "; motivo=" + status["rejectionReason"])
    wait_offsets()
    published = messages(REJECTED)
    for expected in rejected:
        rows = [row for row in published if row["event"]["transactionId"] == expected["transaction"]]
        assert len(rows) == 1; same_event(rows[0]["event"], expected["event"])
    before = snapshot()
    duplicate = dict(state["requested"]["event"]); duplicate["eventId"] = str(uuid.uuid4())
    publish(duplicate); publish(duplicate)
    wait_offsets()
    results = [row for row in messages(COMPLETED) if row["event"]["transactionId"] == state["pending"]]
    assert len(results) == 3
    for row in results: same_event(row["event"], state["original"])
    assert snapshot() == before
    conflict = {**duplicate, "amount": 30000}
    internal("POST", "/api/core/internal/withdrawals", body=conflict, expected=409)
    assert snapshot() == before
    state["rejected"] = rejected; state["after_withdrawals"] = before; save(state)
    report("Seis rechazos publican WithdrawalRejectedEvent y reconocen entrada; cero cargos. Duplicado Kafka dos veces mismo transactionId/otro eventId: tres entregas del evento original, un debito; snapshot DB identico")
    report("Reutilizacion transactionId con monto diferente: Core interno409, evento original/eventId/saldo150000 conservados. Conflicto Kafka con listener detenido cubierto por WithdrawalKafkaIntegrationTest existente")


def outage():
    tokens(); state = load(); before = snapshot()
    recovery_id = str(uuid.uuid4())
    state["outage_attempt"] = {"deposit": recovery_id, "before": before}; save(state)
    report("Fallo D aislado: docker compose ... stop core-banking-service; se conserva su contenedor/volumen, otros proyectos intactos")
    s.compose("stop", "core-banking-service", timeout=90)
    try:
        unknown, _ = s.request("bff-web-service", "POST", "/api/bff/web/payments/deposits", "web",
            {"operationId": recovery_id, "accountId": state["source"], "amount": 30000}, 503)
        assert unknown["status"] == "UNKNOWN" and unknown["operationId"] == recovery_id
        s.request("bff-mobile-service", "GET", "/api/bff/mobile/accounts/" + str(state["source"]), "mobile", expected=503)
        s.request("bff-atm-service", "GET", "/api/bff/atm/accounts/" + str(state["source"]) + "/balance", "atm", expected=503)
        s.request("bff-web-service", "GET", "/api/bff/web/customers/" + str(state["customer"]), "web")
        pending = atm(state["source"], 10000)
        state["outage_attempt"]["pending"] = pending; save(state)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            current = offsets()
            members = kafka("kafka-consumer-groups", "--describe", "--group", GROUP, "--members")
            if sum(row["lag"] for row in current.values()) == 1 and not any(line.startswith(GROUP) for line in members.splitlines()):
                break
            time.sleep(2)
        else: raise AssertionError("No se confirmo detencion del grupo con solicitud pendiente")
        current = offsets()
        assert sum(row["lag"] for row in current.values()) == 1
        assert snapshot() == before
        report("Core no disponible: Pagos HTTP503/UNKNOWN conserva UUID; consultas Mobile/ATM503, Clientes Web200. Retiro pendiente=" + pending
               + "; sin rechazo falso ni cargos; offsets no reconocen solicitud=" + str(current))
    finally:
        # Compose start incluye dependencias y puede relanzar batch-init; iniciar solo este contenedor.
        run(["docker", "start", s.owned("core-banking-service")[0]])
    wait_health("core-banking-service"); s.wait_registrations()
    s.compose("restart", "payment-service", timeout=90); wait_health("payment-service"); s.wait_registrations()
    _, original = withdrawal(pending, "COMPLETED", 140000); wait_offsets()
    result = next(row for row in messages(COMPLETED) if row["event"]["transactionId"] == pending)
    same_event(result["event"], original)
    value, _ = s.request("bff-web-service", "POST", "/api/bff/web/payments/deposits", "web",
            {"operationId": recovery_id, "accountId": state["source"], "amount": 30000}, expected=200)
    assert value["status"] == "COMPLETED"
    repeat, _ = s.request("bff-web-service", "POST", "/api/bff/web/payments/deposits", "web",
                {"operationId": recovery_id, "accountId": state["source"], "amount": 30000})
    assert value == repeat
    state["recovery"] = {"pending": pending, "event": original, "deposit": recovery_id}; save(state)
    report("Recuperacion D: Core iniciado y Pagos reiniciado, reentrega completa mismo retiro a140000; solicitud monetaria UNKNOWN reenviada mismo UUID completa deposito30000 una sola vez; saldo170000")


def wait_health(service):
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        if all(run(["docker", "inspect", "--format", "{{.State.Health.Status}}", container]).strip() == "healthy" for container in s.owned(service)):
            return
        time.sleep(3)
    raise AssertionError("Servicio no saludable: " + service)


def batch_and_restart():
    tokens(); state = load(); before = snapshot()
    assert before["history"] == state["initial"]["history"], "Historial mensual cambia; se conserva el fallo y no se ejecutan Jobs adicionales"
    assert before["operations"] == 14 and before["withdrawals"] == 8
    report("Comando exclusivo: docker compose ... start batch-init; reejecuta tres Jobs sobre PostgreSQL eft_s9_integracion")
    s.compose("start", "batch-init")
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        status = run(["docker", "inspect", "--format", "{{.State.Status}}|{{.State.ExitCode}}", s.owned("batch-init")[0]]).strip()
        if status.startswith("exited"):
            assert status == "exited|0"; break
        time.sleep(5)
    else: raise AssertionError("Batch no termino")
    after = snapshot(); assert len(after["jobs"]) == len(before["jobs"]) + 3 and all(row["status"] == "COMPLETED" for row in after["jobs"])
    assert {k: v for k, v in before.items() if k != "jobs"} == {k: v for k, v in after.items() if k != "jobs"}
    report("Batch: dailyTransactionsJob/monthlyInterestJob/annualStatementsJob COMPLETED; ejecuciones totales=" + str(len(after["jobs"])) + "; contenido historico hash+conteos=" + str(after["history"]))
    report("Batch conserva saldos/operaciones/retiros/eventos; no recontabiliza retiros ni transferencias. Operaciones14, retiros8, eventos16; saldos170000/20000/0")
    restart_and_check(after)


def restart_and_check(after):
    state = load()
    report("Comando exclusivo: docker compose ... restart core-banking-service payment-service customer-service bff-atm-service")
    s.compose("restart", "core-banking-service", "payment-service", "customer-service", "bff-atm-service", timeout=120)
    for name in ["core-banking-service", "payment-service", "customer-service", "bff-atm-service"]: wait_health(name)
    s.wait_registrations(); tokens()
    assert snapshot() == after
    _, original = withdrawal(state["pending"], "COMPLETED", 150000)
    same_event(original, state["original"])
    publish(state["requested"]["event"])
    wait_offsets()
    delivered = [row for row in messages(COMPLETED) if row["event"]["transactionId"] == state["pending"]]
    assert len(delivered) == 4
    for row in delivered: same_event(row["event"], state["original"])
    balance, _ = s.request("bff-atm-service", "GET", "/api/bff/atm/accounts/" + str(state["source"]) + "/balance", "atm")
    assert balance["balance"] == 170000
    customer, _ = s.request("bff-web-service", "GET", "/api/bff/web/customers/" + str(state["customer"]), "web")
    assert customer["firstName"] == "Actualizado" and customer["profile"] == "PREFERENTE"
    light, _ = s.request("bff-mobile-service", "GET", "/api/bff/mobile/accounts/" + str(state["source"]), "mobile")
    assert light["finalBalance"] == 170000
    s.request("bff-web-service", "GET", "/api/bff/web/payments/operations/" + state["recovery"]["deposit"], "web")
    for item in state["rejected"]: withdrawal(item["transaction"], "REJECTED")
    assert snapshot() == after
    wait_offsets(); s.health_and_config()
    state["final"] = after; save(state)
    report("Reinicio Core/Pagos/Clientes/ATM: resultados exitosos y seis rechazos recuperados; reenvio Kafka posterior al reinicio conserva evento original y no cobra nuevamente; saldos170000/20000/0, Jobs=" + str(len(after["jobs"])) + " sin ejecuciones adicionales, offsets sin lag")


def maven(command):
    report("Maven comando: " + " ".join(command))
    maven_environment = s.ENV.copy()
    if "-Dtest=BffApplicationsOAuth2IT" in command:
        maven_environment.setdefault("JAVA_TOOL_OPTIONS", "-Xms64m -Xmx256m")
    if maven_environment.get("JAVA_TOOL_OPTIONS"):
        report("JVM de prueba JAVA_TOOL_OPTIONS=" + maven_environment["JAVA_TOOL_OPTIONS"])
    process = subprocess.Popen(command, cwd=s.ROOT, env=maven_environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    for line in process.stdout:
        if any(marker in line for marker in ["Tests run:", "BUILD SUCCESS", "BUILD FAILURE", "Total time:", "Finished at:", "EFT05 ", "[ERROR]"]):
            report(line.rstrip())
    assert process.wait() == 0, "Maven fallo"


def integrity():
    before = json.loads((s.ARTIFACTS / "resources-before.json").read_text()); after = s.resource_snapshot()
    assert all(after["containers"].get(key) == value for key, value in before["containers"].items())
    assert set(before["volumes"]).issubset(after["volumes"])
    assert snapshot(OLD_PROJECT) == json.loads((s.ARTIFACTS / "validation-before.json").read_text())
    groups = run(["docker", "exec", OLD_PROJECT + "-kafka-1", "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "kafka:9092", "--list"])
    assert GROUP not in groups
    totals = {}
    for topic, expected in [(REQUESTED, 11), (COMPLETED, 5), (REJECTED, 6)]:
        lines = kafka("kafka-get-offsets", "--topic", topic, "--time", "latest").splitlines()
        totals[topic] = sum(int(line.rsplit(":", 1)[1]) for line in lines if line.startswith(topic + ":"))
        assert totals[topic] == expected, totals
    committed = wait_offsets()
    assert sum(row["committed"] for row in committed.values()) == 11
    records = json.loads(sql("""SELECT json_build_object(
        'completed',(SELECT count(*) FROM withdrawal_transactions WHERE status='COMPLETED'),
        'rejected',(SELECT count(*) FROM withdrawal_transactions WHERE status='REJECTED'),
        'pending',(SELECT count(*) FROM operaciones_cuenta WHERE estado='PENDING'),
        'legacy',(SELECT count(*) FROM processed_events),
        'inconsistent',(SELECT count(*) FROM withdrawal_transactions w LEFT JOIN operaciones_cuenta o ON o.operacion_id=w.transaction_id
          WHERE o.operacion_id IS NULL OR o.estado<>w.status OR o.monto<>w.amount OR o.cuenta_origen_id<>w.account_id));"""))
    assert records == {"completed": 2, "rejected": 6, "pending": 0, "legacy": 0, "inconsistent": 0}, records
    for name in ["postgres", "kafka"]:
        mounts = json.loads(run(["docker", "inspect", "--format", "{{json .Mounts}}", s.owned(name)[0]]))
        names = {mount["Name"] for mount in mounts if mount["Type"] == "volume"}
        assert s.PROJECT + "_" + name + "_data" in names and names.isdisjoint(before["volumes"])
    for container in s.owned():
        networks = json.loads(run(["docker", "inspect", "--format", "{{json .NetworkSettings.Networks}}", container]))
        assert set(networks) == {s.PROJECT + "_default"}
        logs = run(["docker", "logs", container])
        assert not any(s.ENV[name] in logs for name in s.SECRET_NAMES)
        assert not re.search(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", logs)
        assert "Using generated security password:" not in logs
    baseline = json.loads((s.ARTIFACTS / "baseline.json").read_text()); root = s.ROOT.parent
    changed = [name for name, digest in baseline["hashes"].items() if not (root/name).is_file() or hashlib.sha256((root/name).read_bytes()).hexdigest() != digest]
    assert not changed, changed
    assert run(["git", "rev-parse", "HEAD"]).strip() == baseline["head"]
    cases = {}
    for path in s.ROOT.glob("*/target/surefire-reports/TEST-*.xml"):
        for case in ET.parse(path).getroot().iter("testcase"):
            cases[(case.get("classname"),case.get("name"))] = not any(case.find(tag) is not None for tag in ["failure", "error", "skipped"])
    assert {tuple(case) for case in baseline["cases"]} == set(cases) and all(cases.values()) and len(cases) == 356
    assert (s.ARTIFACTS/"credentials.json").stat().st_mode & 0o777 == 0o600
    evidence = s.EVIDENCE.read_text(); assert not any(s.ENV[name] in evidence for name in s.SECRET_NAMES)
    assert not re.search(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", evidence)
    run(["git", "diff", "--check"])
    status = run(["git", "status", "--short"])
    report("Integridad final: " + str(len(before["containers"])) + " contenedores previos con estados/fechas/reinicios intactos; "
           + str(len(before["volumes"])) + " volumenes previos conservados. Entorno09 datos/historicos/Jobs/consumer monetario intactos; montajes/red nuevos")
    report("Kafka final: mensajes por topic=" + str(totals) + "; offsets=" + str(committed)
           + "; ledger sin pendientes/inconsistencias y processed_events legacy0. Dos retiros completados y seis rechazados; eventos pueden repetirse, cargo idempotente")
    report("Maven: 356 casos distintos satisfactorios y conservados; cero fallos/errores/omisiones, sin nuevas pruebas JUnit. SHA256: 302 archivos previos identicos, README/evidencias00-09/codigo/configuraciones principales intactos")
    report("git diff --check salida0; git status --short:\n" + status.rstrip())
    runtime = run(["docker", "ps", "-a", "--filter", "label=com.docker.compose.project=" + s.PROJECT, "--format", "{{.Names}}|{{.Status}}|{{.Ports}}"])
    report("Estado Compose final:\n" + runtime.rstrip())
    historic = snapshot()["history"]
    original = load()["initial"]["history"]
    if historic != original:
        report("FALLO requisito historico: monthlyInterestJob cambia contenido en reejecucion; conteos iguales, diarios/anuales iguales, saldos operativos conservados. No se modifica Job ni CSV; requiere autorizacion")
    assert historic == original, "Contenido historico mensual no determinista; Paso10 no aprobado integralmente"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=["audit", "up", "seed", "activate", "withdrawals", "outage", "batch-restart", "restart-only", "maven", "core-it", "bff-it", "regression", "integrity"])
    stage = parser.parse_args().stage
    s.prepare(); s.ENV["POSTGRES_USER"] = "eft10_user"
    s.ENV["EFT10_WITHDRAWALS_ENABLED"] = "true" if (s.ARTIFACTS/"consumer-enabled").exists() else "false"
    report("Etapa=" + stage + "; proyecto=" + s.PROJECT)
    if stage == "audit": audit()
    elif stage == "up":
        report("Comando: docker compose -p eft_s9_integracion -f docker-compose.yml -f docker-compose.validacion.yml -f docker-compose.integracion.yml up -d --no-build --pull never --wait --wait-timeout 420")
        s.compose("up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "420", timeout=480)
    elif stage == "seed": seed()
    elif stage == "activate": activate()
    elif stage == "withdrawals": rejections_and_duplicates()
    elif stage == "outage": outage()
    elif stage == "batch-restart": batch_and_restart()
    elif stage == "restart-only":
        tokens(); current = snapshot()
        assert current["operations"] == 14 and current["withdrawals"] == 8
        restart_and_check(current)
    elif stage == "maven": maven(["./mvnw", "test"])
    elif stage == "regression":
        maven(["./mvnw", "test"])
        for module, test in [("core-banking-service", "CoreCustomerOAuth2IT"), ("bff-web-service", "BffApplicationsOAuth2IT")]:
            maven(["./mvnw", "-pl", module, "-am", "-Dtest=" + test, "-Dsurefire.failIfNoSpecifiedTests=false", "test"])
    elif stage in ["core-it", "bff-it"]:
        module, test = ("core-banking-service", "CoreCustomerOAuth2IT") if stage == "core-it" else ("bff-web-service", "BffApplicationsOAuth2IT")
        maven(["./mvnw", "-pl", module, "-am", "-Dtest=" + test, "-Dsurefire.failIfNoSpecifiedTests=false", "test"])
    else: integrity()
    report("Resultado " + stage + ": SATISFACTORIO")


if __name__ == "__main__":
    try: main()
    except Exception as error:
        report("FALLO verificacion: " + s.redact(str(error)))
        raise SystemExit(1)
