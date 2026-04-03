export type Assignment = {
  id: string;
  name: string;
  course: string;
  dueDate: string;
};

export const MOCK_ASSIGNMENTS: Assignment[] = [
  {
    id: "1",
    name: "Midterm 1",
    course: "SFWRENG 2C03",
    dueDate: "Oct 10, 10:30 AM",
  },
  {
    id: "2",
    name: "Lab Report",
    course: "ENG 1P13",
    dueDate: "Oct 12, 11:59 PM",
  },
  {
    id: "3",
    name: "Assignment 2",
    course: "MATH 2Z03",
    dueDate: "Oct 14, 5:00 PM",
  },
];
