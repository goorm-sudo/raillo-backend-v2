-- KEYS: meta, stops, seats, fares, reservations, occupancy, deadlines, idempotency
-- ARGV[1]: 서버가 구성한 요청 JSON. price가 없으면 원자적으로 읽은 운임 기준정보를 반환한다.
-- 활성 예약에는 TTL/HEXPIRE를 사용하지 않는다. 만료는 Redis TIME과 expiresAt으로 판정한다.
local function result(code, reservation, quote)
    return cjson.encode({code = code, reservation = reservation, quote = quote})
end

local function decode(raw)
    if not raw then return nil end
    local ok, value = pcall(cjson.decode, raw)
    if not ok or type(value) ~= 'table' then return nil end
    return value
end

local function integer(value, minimum, maximum)
    return type(value) == 'number' and value >= minimum and value <= maximum and value == math.floor(value)
end

local function identifier(value)
    return type(value) == 'string' and #value <= 19 and string.match(value, '^[1-9]%d*$') ~= nil
end

local function token(value, maximum)
    return type(value) == 'string' and #value >= 1 and #value <= maximum
        and string.match(value, '^[%w_-]+$') ~= nil
end

local function money(value)
    return type(value) == 'string' and #value <= 32
        and (string.match(value, '^%d+$') ~= nil or string.match(value, '^%d+%.%d+$') ~= nil)
end

local passengerTypes = {ADULT = true, CHILD = true, INFANT = true, SENIOR = true,
    DISABLED_HEAVY = true, DISABLED_LIGHT = true, VETERAN = true}
local carTypes = {STANDARD = true, FIRST_CLASS = true}
local statuses = {HELD = true, CONFIRMING = true, CONFIRMED = true, CANCELLED = true, EXPIRED = true}

if #KEYS ~= 8 or #ARGV ~= 1 then return result('INVALID_REQUEST') end
local request = decode(ARGV[1])
if not request or not identifier(request.trainScheduleId)
    or not identifier(request.departureStationId) or not identifier(request.arrivalStationId)
    or not token(request.memberNo, 64) or not token(request.idempotencyKey, 128)
    or not token(request.reservationId, 80) or not token(request.requestHash, 64)
    or #request.requestHash ~= 64 or type(request.seats) ~= 'table'
    or #request.seats < 1 or #request.seats > 8
    or not integer(request.holdDurationMillis, 1, 86400000)
    or not integer(request.salesCutoffMillis, 0, 86400000) then
    return result('INVALID_REQUEST')
end
local seen = {}
for _, seat in ipairs(request.seats) do
    if type(seat) ~= 'table' or not identifier(seat.seatId)
        or not passengerTypes[seat.passengerType] or seen[seat.seatId] then
        return result('INVALID_REQUEST')
    end
    seen[seat.seatId] = true
end

-- 쓰기 중 WRONGTYPE을 방지하기 위해 아직 사용하지 않는 키도 선검증한다.
local suffixes = {'meta', 'stops', 'seats', 'fares', 'reservations', 'occupancy', 'deadlines', 'idempotency'}
for index, key in ipairs(KEYS) do
    if key ~= 'rail:{schedule:' .. request.trainScheduleId .. '}:' .. suffixes[index] then
        return result('INVALID_REQUEST')
    end
    local expected = index == 7 and 'zset' or 'hash'
    local actual = redis.call('TYPE', key).ok
    if actual ~= 'none' and (actual ~= expected or redis.call('PTTL', key) ~= -1) then
        return result('INCONSISTENT')
    end
end

local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local generation = redis.call('HGET', KEYS[1], 'generation')
local referenceVersion = redis.call('HGET', KEYS[1], 'version')

local function validReservation(reservation, owner)
    if not reservation or reservation.id ~= owner or reservation.schemaVersion ~= 1
        or reservation.trainScheduleId ~= request.trainScheduleId
        or not token(reservation.memberNo, 64) or not token(reservation.requestHash, 64)
        or not statuses[reservation.status] or reservation.generation ~= generation
        or not token(reservation.fareVersion, 80) or not integer(reservation.version, 1, 9007199254740991)
        or not identifier(reservation.departureStopId) or not identifier(reservation.arrivalStopId)
        or not integer(reservation.departureStopOrder, 0, 255)
        or not integer(reservation.arrivalStopOrder, 1, 256)
        or reservation.departureStopOrder >= reservation.arrivalStopOrder
        or not integer(reservation.createdAt, 1, 9007199254740991)
        or not integer(reservation.expiresAt, 1, 9007199254740991)
        or reservation.expiresAt <= reservation.createdAt or not money(reservation.totalFare)
        or type(reservation.seatReservations) ~= 'table'
        or #reservation.seatReservations < 1 or #reservation.seatReservations > 8 then
        return false
    end
    local ids = {}
    for _, seat in ipairs(reservation.seatReservations) do
        if type(seat) ~= 'table' or not identifier(seat.seatId) or ids[seat.seatId]
            or not passengerTypes[seat.passengerType] or not carTypes[seat.carType] or not money(seat.fare) then
            return false
        end
        ids[seat.seatId] = true
    end
    return true
end

local function active(reservation)
    return reservation.status == 'CONFIRMING' or reservation.status == 'CONFIRMED'
        or (reservation.status == 'HELD' and reservation.expiresAt > now)
end

local idempotencyField = request.memberNo .. ':' .. request.idempotencyKey
local previous = redis.call('HGET', KEYS[8], idempotencyField)
if previous then
    local entry = decode(previous)
    if not entry or not token(entry.reservationId, 80) or not token(entry.requestHash, 64) then
        return result('INCONSISTENT')
    end
    if entry.requestHash ~= request.requestHash then return result('IDEMPOTENCY_CONFLICT') end
    if entry.completed ~= true then return result('INCONSISTENT') end
    local reservation = decode(redis.call('HGET', KEYS[5], entry.reservationId))
    if not validReservation(reservation, entry.reservationId) or reservation.memberNo ~= request.memberNo
        or reservation.requestHash ~= request.requestHash then return result('INCONSISTENT') end
    if active(reservation) then
        for _, seat in ipairs(reservation.seatReservations) do
            for section = reservation.departureStopOrder, reservation.arrivalStopOrder - 1 do
                if redis.call('HGET', KEYS[6], seat.seatId .. ':' .. section) ~= reservation.id then
                    return result('INCONSISTENT')
                end
            end
        end
        if reservation.status == 'HELD'
            and tonumber(redis.call('ZSCORE', KEYS[7], reservation.id)) ~= reservation.expiresAt then
            return result('INCONSISTENT')
        end
    elseif reservation.status == 'HELD' then
        -- Worker 전이라도 응답은 논리적으로 만료된 상태. 저장/기한 연장은 하지 않는다.
        reservation.status = 'EXPIRED'
    end
    return result('REPLAY', reservation)
end

if redis.call('HEXISTS', KEYS[5], request.reservationId) == 1 then return result('ID_CONFLICT') end
if redis.call('HGET', KEYS[1], 'status') ~= 'READY'
    or redis.call('HGET', KEYS[1], 'inventoryReady') ~= '1' then return result('NOT_READY') end
if not token(generation, 80) or not token(referenceVersion, 80) then return result('INCONSISTENT') end

local price = request.price
if price and price ~= cjson.null then
    if type(price) ~= 'table' then return result('INVALID_REQUEST') end
    if price.version ~= referenceVersion or price.generation ~= generation then return result('REFERENCE_CHANGED') end
end

local departureRaw = redis.call('HGET', KEYS[2], request.departureStationId)
local arrivalRaw = redis.call('HGET', KEYS[2], request.arrivalStationId)
if not departureRaw or not arrivalRaw then return result('INVALID_REQUEST') end
local departure, arrival = decode(departureRaw), decode(arrivalRaw)
if not departure or not arrival or not identifier(departure.id) or not identifier(arrival.id)
    or not integer(departure.ordinal, 0, 255) or not integer(arrival.ordinal, 0, 256)
    or not integer(departure.departureAt, 1, 9007199254740991) then return result('INCONSISTENT') end
if departure.ordinal >= arrival.ordinal then return result('INVALID_REQUEST') end
local expiresAt = math.min(now + request.holdDurationMillis, departure.departureAt - request.salesCutoffMillis)
if expiresAt <= now then return result('SALES_CLOSED') end

local quotedSeats = {}
local carType
for _, requested in ipairs(request.seats) do
    local raw = redis.call('HGET', KEYS[3], requested.seatId)
    if not raw then return result('INVALID_REQUEST') end
    local seat = decode(raw)
    if not seat or not carTypes[seat.carType] or type(seat.available) ~= 'boolean' then
        return result('INCONSISTENT')
    end
    if not seat.available then return result('INVALID_REQUEST') end
    if carType and carType ~= seat.carType then return result('INVALID_CAR_TYPE') end
    carType = seat.carType
    local fare = redis.call('HGET', KEYS[4], request.departureStationId .. ':' .. request.arrivalStationId .. ':' .. carType)
    if not money(fare) then return result('INCONSISTENT') end
    table.insert(quotedSeats, {seatId = requested.seatId, passengerType = requested.passengerType,
        carType = carType, baseFare = fare})
end

if not price or price == cjson.null then
    return result('QUOTE', nil, {version = referenceVersion, generation = generation, seats = quotedSeats})
end
if type(price.seats) ~= 'table' or #price.seats ~= #request.seats or not money(price.totalFare) then
    return result('INVALID_REQUEST')
end
for index, seat in ipairs(price.seats) do
    local requested = request.seats[index]
    if type(seat) ~= 'table' or seat.seatId ~= requested.seatId or seat.passengerType ~= requested.passengerType
        or seat.carType ~= carType or not money(seat.fare) then return result('INVALID_REQUEST') end
end

-- 전체 좌석/구간을 선검증한다. 충돌 시 빈 구간도 선점하지 않는다.
local fields, owners = {}, {}
for _, seat in ipairs(request.seats) do
    for section = departure.ordinal, arrival.ordinal - 1 do
        local field = seat.seatId .. ':' .. section
        table.insert(fields, field)
        local owner = redis.call('HGET', KEYS[6], field)
        if owner then
            local reservation = owners[owner]
            if not reservation then
                reservation = decode(redis.call('HGET', KEYS[5], owner))
                if not validReservation(reservation, owner) then return result('INCONSISTENT') end
                owners[owner] = reservation
            end
            local ownsSeat = false
            for _, heldSeat in ipairs(reservation.seatReservations) do
                if heldSeat.seatId == seat.seatId then ownsSeat = true end
            end
            if not ownsSeat or section < reservation.departureStopOrder or section >= reservation.arrivalStopOrder then
                return result('INCONSISTENT')
            end
            if active(reservation) then
                return result(reservation.status == 'CONFIRMED' and 'SOLD_CONFLICT' or 'SEAT_CONFLICT')
            end
        end
    end
end

local reservation = {id = request.reservationId, memberNo = request.memberNo, trainScheduleId = request.trainScheduleId,
    departureStopId = departure.id, arrivalStopId = arrival.id,
    departureStopOrder = departure.ordinal, arrivalStopOrder = arrival.ordinal,
    seatReservations = price.seats, totalFare = price.totalFare, fareVersion = referenceVersion,
    schemaVersion = 1, generation = generation, status = 'HELD', version = 1,
    requestHash = request.requestHash, createdAt = now, expiresAt = expiresAt}
-- 직렬화도 첫 쓰기 전에 완료한다. Lua 오류는 이미 실행한 쓰기를 롤백하지 않는다.
local encoded = cjson.encode(reservation)
local incomplete = cjson.encode({requestHash = request.requestHash, reservationId = request.reservationId, completed = false})
local complete = cjson.encode({requestHash = request.requestHash, reservationId = request.reservationId, completed = true})
local response = result('CREATED', reservation)
redis.call('HSET', KEYS[8], idempotencyField, incomplete)
redis.call('HSET', KEYS[5], request.reservationId, encoded)
for _, field in ipairs(fields) do redis.call('HSET', KEYS[6], field, request.reservationId) end
redis.call('ZADD', KEYS[7], expiresAt, request.reservationId)
redis.call('HSET', KEYS[8], idempotencyField, complete)
return response
