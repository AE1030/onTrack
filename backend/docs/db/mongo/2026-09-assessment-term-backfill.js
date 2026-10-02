// Assessment table term backfill, and the index swap the unique constraint needs.
//
// Flyway versions Postgres only. Mongo data fixes are scripts a human runs once, per
// environment, in order. Run this with mongosh against dev first, then prod:
//
//     mongosh "$MONGODB_URI" --file 2026-09-assessment-term-backfill.js
//
// RUN THIS BEFORE DEPLOYING the build that carries unique = true on
// AssessmentTableDocument. Two separate things would otherwise break the deploy:
//
//   1. Mongo treats a missing field as null for uniqueness, so documents with no term
//      collide with each other and the index build fails.
//   2. idx_assessment_student_course_term ALREADY EXISTS as a non-unique index. Mongo does
//      not modify an index in place: createIndex with the same name and different options
//      fails with IndexOptionsConflict (code 85). Spring Data calls createIndex at startup
//      because spring.data.mongodb.auto-index-creation=true, and that failure propagates out
//      of context refresh -- the application does not boot. The old index has to be dropped
//      before the new definition can be created.
//
// Every step is safe to run twice.

// ---------------------------------------------------------------- 1. What needs fixing?
const missing = db.assessmentTable.countDocuments({
  $or: [{ term: null }, { term: { $exists: false } }]
});
print(`documents with no term: ${missing}`);

// ---------------------------------------------------------------- 2. Give them the current term.
// Every such document predates multi-term, so the current term is the only term it can mean.
// Keep this literal in step with app.current-term AT THE TIME YOU RUN IT, not at the time you
// read it. If the term has rolled over since this script was written, stop and check what those
// documents actually belong to before changing the value.
const result = db.assessmentTable.updateMany(
  { $or: [{ term: null }, { term: { $exists: false } }] },
  { $set: { term: "Winter 2026" } }
);
print(`documents updated: ${result.modifiedCount}`);

// ---------------------------------------------------------------- 3. Duplicate check.
// Must find nothing. A non-empty result means two documents already share a student, course
// and term, so the unique index cannot be built.
//
// To resolve one by hand: read both documents, keep the one with the more complete schemes (or
// the later lastGradedAt on its rows), and delete the other by _id. Do not merge them
// automatically -- a student's marks are not something to guess at.
const duplicates = db.assessmentTable.aggregate([
  {
    $group: {
      _id: { studentId: "$studentId", courseCode: "$courseCode", term: "$term" },
      n: { $sum: 1 },
      ids: { $push: "$_id" }
    }
  },
  { $match: { n: { $gt: 1 } } }
]).toArray();

if (duplicates.length > 0) {
  print(`DUPLICATES FOUND (${duplicates.length}) -- resolve these, then rerun. Nothing changed.`);
  duplicates.forEach((d) => printjson(d));
  quit(1);
}
print("no duplicates");

// ---------------------------------------------------------------- 4. Swap the index.
// Same name, same keys, unique added. Dropping first is what avoids IndexOptionsConflict.
const NAME = "idx_assessment_student_course_term";
const KEYS = { studentId: 1, courseCode: 1, term: 1 };

const existing = db.assessmentTable.getIndexes().find((i) => i.name === NAME);

if (existing && existing.unique) {
  print(`${NAME} is already unique: nothing to do`);
} else {
  if (existing) {
    print(`dropping the non-unique ${NAME}`);
    db.assessmentTable.dropIndex(NAME);
  }
  // Created here rather than left to the application's auto-index-creation, so the order is
  // deterministic. The app's createIndex on boot is then a no-op: identical name, keys and
  // options do not conflict.
  db.assessmentTable.createIndex(KEYS, { name: NAME, unique: true });
  print(`${NAME} rebuilt as unique`);
}

// ---------------------------------------------------------------- 5. Confirm.
printjson(db.assessmentTable.getIndexes().find((i) => i.name === NAME));
