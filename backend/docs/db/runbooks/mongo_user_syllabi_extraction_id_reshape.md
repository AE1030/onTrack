# V2 — reshape the `userSyllabiExtraction` `_id`

**Destructive. Not run by the implementation.**

```js
db.userSyllabiExtraction.deleteMany({})
```

## Timing: this is cleanup, not a prerequisite

Nothing breaks if it is never run. The only read path into this collection is
`UserSyllabusRepository.findByCourseCodeAndTerm`, which now matches on `_id.studentId`,
`_id.courseCode` and `_id.term`. Documents written under the old two-part `_id` cannot match that
query, so they are never loaded, never deserialized, and never reach the ownership assert. They
simply become unreachable dead weight, and students regenerate their extractions on the next upload.

So run this whenever convenient — before or after the deploy — or not at all if the storage does not
bother you. What it buys is a clean collection rather than one carrying an invisible layer of
orphaned documents.

## Why the collection is emptied rather than migrated

`UserSyllabusDocument` used to inherit its `_id` from `AbstractSyllabusDocument`: a
`CourseTermId` of `(course_code, term)`, with the owner as an ordinary field alongside it. Two
students who uploaded the same course in the same term therefore produced the *same* `_id`, and
`save()` upserts by `_id` — so the second upload silently overwrote the first.

This was never a historical accident that had already worked itself through. For as long as the id
kept that shape, the next two students to collide would overwrite each other again.

The new id is `StudentCourseTermId(studentId, courseCode, term)`. Mongo's `_id` is immutable, so
existing documents cannot be updated in place — a migration would have to read each document, insert
a copy under the new `_id`, and delete the original. That is worth writing only if the data is worth
keeping, and it is not: extractions are regenerated from the uploaded PDF on the next upload, and
any document that was already overwritten is unrecoverable either way.

## What else changes in the same deploy, for free

The top-level owner field moves from `student_id` to plain `studentId`, matching the four other
owned collections. That rename would ordinarily be its own migration; here it rides along free,
because the old documents it would have stranded are already unreachable via the new `_id`. After
this, no collection in the application stores the owner under a snake_case name — see
`TenantMappingTest.ownerFieldNameIsStable`, which fails if one ever does again.

## Verification after deploy

Indexes are created at startup (`spring.data.mongodb.auto-index-creation=true`). Confirm rather than
assume:

```js
db.userSyllabiExtraction.getIndexes()   // _id_ only; the id now covers tenant lookups
db.assessmentTable.getIndexes()         // idx_assessment_student_course_term
db.calendarEvents.getIndexes()          // idx_calendar_events_student
db.syllabusExtractionJobs.getIndexes()  // idx_extraction_job_student
db.googleCalendarExports.getIndexes()   // idx_calendar_export_student_time
```

Then upload the same syllabus for the same course and term as two different students and confirm two
documents exist, not one:

```js
db.userSyllabiExtraction.find({ "_id.courseCode": "<code>", "_id.term": "<term>" }).count()
```
