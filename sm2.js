/**
 * SuperMemo-2 (SM-2) Spaced Repetition Engine
 * Fully deterministic mathematical state machine
 */
const SM2_DEFAULTS = { reps: 0, ef: 2.5, interval: 0 };

function sm2CreateCard(id, q, a) {
  return {
    id,
    q,
    a,
    reps: SM2_DEFAULTS.reps,
    ef: SM2_DEFAULTS.ef,
    interval: SM2_DEFAULTS.interval,
    nextDate: Date.now()
  };
}

function sm2Review(card, rating) {
  if (![0, 3, 4, 5].includes(rating)) {
    throw new Error(`Invalid rating ${rating}. Must be 0 (Again), 3 (Hard), 4 (Good), or 5 (Easy).`);
  }

  let { reps, ef, interval } = card;
  const newEf = ef + (0.1 - (5 - rating) * (0.08 + (5 - rating) * 0.02));
  ef = Math.max(1.3, newEf);

  if (rating < 3) {
    reps = 0;
    interval = 1;
  } else {
    if (reps === 0) interval = 1;
    else if (reps === 1) interval = 6;
    else interval = Math.round(interval * ef);
    reps += 1;
  }

  const nextDate = Date.now() + interval * 24 * 60 * 60 * 1000;

  return {
    ...card,
    reps,
    ef: Math.round(ef * 100) / 100,
    interval,
    nextDate
  };
}

function sm2GetDueCards(cards) {
  const now = Date.now();
  return cards.filter((c) => c.nextDate <= now);
}

function sm2CountDue(cards) {
  return sm2GetDueCards(cards).length;
}

function sm2UpdateCardInDeck(cards, updatedCard) {
  return cards.map((c) => (c.id === updatedCard.id ? updatedCard : c));
}

